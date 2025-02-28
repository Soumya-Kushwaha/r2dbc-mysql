/*
 * Copyright 2023 asyncer.io projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.asyncer.r2dbc.mysql.client;

import io.asyncer.r2dbc.mysql.ConnectionContext;
import io.asyncer.r2dbc.mysql.MySqlSslConfiguration;
import io.asyncer.r2dbc.mysql.internal.util.OperatorUtils;
import io.asyncer.r2dbc.mysql.message.client.ClientMessage;
import io.asyncer.r2dbc.mysql.message.client.ExitMessage;
import io.asyncer.r2dbc.mysql.message.server.ServerMessage;
import io.asyncer.r2dbc.mysql.message.server.WarningMessage;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.r2dbc.spi.R2dbcException;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.netty.Connection;
import reactor.netty.FutureMono;
import reactor.util.context.Context;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.asyncer.r2dbc.mysql.internal.util.AssertUtils.requireNonNull;

/**
 * An implementation of client based on the Reactor Netty project.
 */
final class ReactorNettyClient implements Client {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ReactorNettyClient.class);

    private static final boolean DEBUG_ENABLED = logger.isDebugEnabled();

    private static final boolean INFO_ENABLED = logger.isInfoEnabled();

    private static final Consumer<ReferenceCounted> RELEASE = ReferenceCounted::release;

    private final Connection connection;

    private final ConnectionContext context;

    private final Sinks.Many<ClientMessage> requests = Sinks.many().unicast().onBackpressureBuffer();

    /**
     * TODO: use new API.
     */
    @SuppressWarnings("deprecation")
    private final reactor.core.publisher.EmitterProcessor<ServerMessage> responseProcessor =
        reactor.core.publisher.EmitterProcessor.create(false);

    private final RequestQueue requestQueue = new RequestQueue();

    private final AtomicBoolean closing = new AtomicBoolean();

    ReactorNettyClient(Connection connection, MySqlSslConfiguration ssl, ConnectionContext context) {
        requireNonNull(connection, "connection must not be null");
        requireNonNull(context, "context must not be null");
        requireNonNull(ssl, "ssl must not be null");

        this.connection = connection;
        this.context = context;

        // Note: encoder/decoder should before reactor bridge.
        connection.addHandlerLast(EnvelopeSlicer.NAME, new EnvelopeSlicer())
            .addHandlerLast(MessageDuplexCodec.NAME,
                new MessageDuplexCodec(context, this.closing, this.requestQueue));

        if (ssl.getSslMode().startSsl()) {
            connection.addHandlerFirst(SslBridgeHandler.NAME, new SslBridgeHandler(context, ssl));
        }

        if (logger.isTraceEnabled()) {
            logger.debug("Connection tracking logging is enabled");

            connection.addHandlerFirst(LoggingHandler.class.getSimpleName(),
                new LoggingHandler(ReactorNettyClient.class, LogLevel.TRACE));
        }

        ResponseSink sink = new ResponseSink();

        connection.inbound().receiveObject()
            .doOnNext(it -> {
                if (it instanceof ServerMessage) {
                    if (it instanceof ReferenceCounted) {
                        ((ReferenceCounted) it).retain();
                    }
                    sink.next((ServerMessage) it);
                } else {
                    // ReferenceCounted will released by Netty.
                    throw ClientExceptions.unsupportedProtocol(it.getClass().getTypeName());
                }
            })
            .onErrorResume(this::resumeError)
            .subscribe(new ResponseSubscriber(sink));

        this.requests.asFlux()
            .concatMap(message -> {
                if (DEBUG_ENABLED) {
                    logger.debug("Request: {}", message);
                }

                return connection.outbound().sendObject(message);
            })
            .onErrorResume(this::resumeError)
            .doAfterTerminate(this::handleClose)
            .subscribe();
    }

    @Override
    public <T> Flux<T> exchange(ClientMessage request,
        BiConsumer<ServerMessage, SynchronousSink<T>> handler) {
        requireNonNull(request, "request must not be null");

        return Mono.<Flux<T>>create(sink -> {
            if (!isConnected()) {
                if (request instanceof Disposable) {
                    ((Disposable) request).dispose();
                }
                sink.error(ClientExceptions.exchangeClosed());
                return;
            }

            Flux<T> responses = OperatorUtils.discardOnCancel(responseProcessor
                    .doOnSubscribe(ignored -> emitNextRequest(request))
                    .handle(handler)
                    .doOnTerminate(requestQueue))
                .doOnDiscard(ReferenceCounted.class, RELEASE);

            requestQueue.submit(RequestTask.wrap(request, sink, responses));
        }).flatMapMany(identity());
    }

    @Override
    public <T> Flux<T> exchange(FluxExchangeable<T> exchangeable) {
        requireNonNull(exchangeable, "exchangeable must not be null");

        return Mono.<Flux<T>>create(sink -> {
            if (!isConnected()) {
                exchangeable.subscribe(request -> {
                    if (request instanceof Disposable) {
                        ((Disposable) request).dispose();
                    }
                }, e -> requests.emitError(e, Sinks.EmitFailureHandler.FAIL_FAST));
                sink.error(ClientExceptions.exchangeClosed());
                return;
            }

            Flux<T> responses = responseProcessor
                .doOnSubscribe(ignored -> exchangeable.subscribe(this::emitNextRequest,
                    e -> requests.emitError(e, Sinks.EmitFailureHandler.FAIL_FAST)))
                .handle(exchangeable)
                .doOnTerminate(() -> {
                    exchangeable.dispose();
                    requestQueue.run();
                });

            requestQueue.submit(RequestTask.wrap(exchangeable, sink, OperatorUtils.discardOnCancel(responses)
                .doOnDiscard(ReferenceCounted.class, RELEASE)
                .doOnCancel(exchangeable::dispose)));
        }).flatMapMany(identity());
    }

    @Override
    public Mono<Void> close() {
        return Mono.<Mono<Void>>create(sink -> {
            if (!closing.compareAndSet(false, true)) {
                // client is closing or closed
                sink.success();
                return;
            }

            requestQueue.submit(RequestTask.wrap(sink, Mono.fromRunnable(() -> {
                Sinks.EmitResult result = requests.tryEmitNext(ExitMessage.INSTANCE);

                if (result != Sinks.EmitResult.OK) {
                    logger.error("Exit message sending failed due to {}, force closing", result);
                }
            })));
        }).flatMap(identity()).onErrorResume(e -> {
            logger.error("Exit message sending failed, force closing", e);
            return Mono.empty();
        }).then(forceClose());
    }

    @Override
    public Mono<Void> forceClose() {
        return FutureMono.deferFuture(() -> connection.channel().close());
    }

    @Override
    public ByteBufAllocator getByteBufAllocator() {
        return connection.outbound().alloc();
    }

    @Override
    public boolean isConnected() {
        return !closing.get() && connection.channel().isOpen();
    }

    @Override
    public void sslUnsupported() {
        connection.channel().pipeline().fireUserEventTriggered(SslState.UNSUPPORTED);
    }

    @Override
    public void loginSuccess() {
        connection.channel().pipeline().fireUserEventTriggered(Lifecycle.COMMAND);
    }

    @Override
    public String toString() {
        return String.format("ReactorNettyClient(%s){connectionId=%d}",
            this.closing.get() ? "closing or closed" : "activating", context.getConnectionId());
    }

    private void emitNextRequest(ClientMessage request) {
        if (isConnected() && requests.tryEmitNext(request) == Sinks.EmitResult.OK) {
            return;
        }

        if (request instanceof Disposable) {
            ((Disposable) request).dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Mono<T> resumeError(Throwable e) {
        drainError(ClientExceptions.wrap(e));

        requests.emitComplete((signalType, emitResult) -> {
            if (emitResult.isFailure()) {
                logger.error("Error: {}", emitResult);
            }

            return false;
        });

        logger.error("Error: {}", e.getLocalizedMessage(), e);

        return (Mono<T>) close();
    }

    private void drainError(R2dbcException e) {
        this.requestQueue.dispose();
        this.responseProcessor.onError(e);
    }

    private void handleClose() {
        if (this.closing.compareAndSet(false, true)) {
            logger.warn("Connection has been closed by peer");
            drainError(ClientExceptions.unexpectedClosed());
        } else {
            drainError(ClientExceptions.expectedClosed());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<T, T> identity() {
        return (Function<T, T>) Identity.INSTANCE;
    }

    private final class ResponseSubscriber implements CoreSubscriber<Object> {

        private final ResponseSink sink;

        private ResponseSubscriber(ResponseSink sink) {
            this.sink = sink;
        }

        @Override
        public Context currentContext() {
            return ReactorNettyClient.this.responseProcessor.currentContext();
        }

        @Override
        public void onSubscribe(Subscription s) {
            ReactorNettyClient.this.responseProcessor.onSubscribe(s);
        }

        @Override
        public void onNext(Object message) {
            // The message is already used, see also constructor.
        }

        @Override
        public void onError(Throwable t) {
            sink.error(t);
        }

        @Override
        public void onComplete() {
            handleClose();
        }
    }

    private final class ResponseSink implements SynchronousSink<ServerMessage> {

        @Override
        public void complete() {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Context currentContext() {
            return ReactorNettyClient.this.responseProcessor.currentContext();
        }

        @Override
        public void error(Throwable e) {
            ReactorNettyClient.this.responseProcessor.onError(ClientExceptions.wrap(e));
        }

        @Override
        public void next(ServerMessage message) {
            if (message instanceof WarningMessage) {
                int warnings = ((WarningMessage) message).getWarnings();
                if (warnings == 0) {
                    if (DEBUG_ENABLED) {
                        logger.debug("Response: {}", message);
                    }
                } else if (INFO_ENABLED) {
                    logger.info("Response: {}, reports {} warning(s)", message, warnings);
                }
            } else if (DEBUG_ENABLED) {
                logger.debug("Response: {}", message);
            }

            ReactorNettyClient.this.responseProcessor.onNext(message);
        }
    }

    private static final class Identity implements Function<Object, Object> {

        private static final Identity INSTANCE = new Identity();

        @Override
        public Object apply(Object o) {
            return o;
        }
    }
}

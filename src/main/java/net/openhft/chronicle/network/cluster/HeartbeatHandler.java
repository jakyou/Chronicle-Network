/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.network.cluster;

import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.network.NetworkContext;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.threads.NamedThreadFactory;
import net.openhft.chronicle.wire.Demarshallable;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import net.openhft.chronicle.wire.WriteMarshallable;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * will periodically send a heatbeat message, the period of this message is defined by {@link
 * HeartbeatHandler#heartbeatIntervalMs} once the heart beat is
 *
 * @author Rob Austin.
 */
public class HeartbeatHandler<T extends NetworkContext> extends AbstractSubHandler<T> implements
        Demarshallable, WriteMarshallable, HeartbeatEventHandler {

    public static class Factory implements Function<ClusterContext, WriteMarshallable>,
            Demarshallable {

        @UsedViaReflection
        private Factory(WireIn w) {
        }

        @Override
        public WriteMarshallable apply(ClusterContext clusterContext) {
            long heartbeatTimeoutMs = clusterContext.heartbeatTimeoutMs();
            long heartbeatIntervalMs = clusterContext.heartbeatIntervalMs();
            return heartbeatHandler(heartbeatTimeoutMs, heartbeatIntervalMs,
                    HeartbeatHandler.class.hashCode());
        }
    }

    public static final ScheduledExecutorService HEARTBEAT_EXECUTOR =
            newSingleThreadScheduledExecutor(new NamedThreadFactory("Heartbeat"));

    private final long heartbeatIntervalMs;
    private volatile long lastTimeMessageReceived;
    private final long heartbeatTimeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean hasHeartbeat = new AtomicBoolean();
    private final AtomicReference<Runnable> self = new AtomicReference<>();

    @UsedViaReflection
    protected HeartbeatHandler(@NotNull WireIn w) {
        heartbeatTimeoutMs = w.read(() -> "heartbeatTimeoutMs").int64();
        heartbeatIntervalMs = w.read(() -> "heartbeatIntervalMs").int64();
        assert heartbeatTimeoutMs >= 1000 :
                "heartbeatTimeoutMs=" + heartbeatTimeoutMs + ", this is too small";
        assert heartbeatIntervalMs >= 500 :
                "heartbeatIntervalMs=" + heartbeatIntervalMs + ", this is too small";

        startHeartbeatCheck();
    }

    private HeartbeatHandler(long heartbeatTimeoutMs, long heartbeatIntervalMs) {
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        assert heartbeatTimeoutMs > heartbeatIntervalMs :
                "heartbeatIntervalMs=" + heartbeatIntervalMs + ", " +
                        "heartbeatTimeoutMs=" + heartbeatTimeoutMs;

        assert heartbeatTimeoutMs >= 1000 :
                "heartbeatTimeoutMs=" + heartbeatTimeoutMs + ", this is too small";
        assert heartbeatIntervalMs >= 500 :
                "heartbeatIntervalMs=" + heartbeatIntervalMs + ", this is too small";
    }

    @Override
    public void onInitialize(WireOut outWire) {

        if (nc().isAcceptor())
            heartbeatHandler(heartbeatTimeoutMs, heartbeatIntervalMs, cid()).writeMarshallable
                    (outWire);

        final WriteMarshallable heartbeatMessage = w -> {
            w.writeDocument(true, d -> d.write(CoreFields.cid).int64(cid()));
            w.writeDocument(false, d -> d.write(() -> "heartbeat").text(""));
        };

        final Runnable task = () -> {
            // we will only publish a heartbeat if the wire out publisher is empty
            if (nc().wireOutPublisher().isEmpty())
                nc().wireOutPublisher().publish(heartbeatMessage);
        };

        HEARTBEAT_EXECUTOR.scheduleAtFixedRate(task, this.heartbeatIntervalMs, this
                .heartbeatIntervalMs, MILLISECONDS);
    }

    private static WriteMarshallable heartbeatHandler(final long heartbeatTimeoutMs,
                                                      final long heartbeatIntervalMs,
                                                      final long cid) {
        return w -> w.writeDocument(true,
                d -> d.writeEventName(CoreFields.csp).text("/")
                        .writeEventName(CoreFields.cid).int64(cid)
                        .writeEventName(CoreFields.handler).typedMarshallable(new
                                HeartbeatHandler(heartbeatTimeoutMs, heartbeatIntervalMs)));
    }

    @Override
    public void writeMarshallable(@NotNull WireOut w) {
        w.write(() -> "heartbeatTimeoutMs").int64(heartbeatTimeoutMs);
        assert heartbeatIntervalMs > 0;
        w.write(() -> "heartbeatIntervalMs").int64(heartbeatIntervalMs);
    }

    @Override
    public void processData(@NotNull WireIn inWire, @NotNull WireOut outWire) {
        inWire.read(() -> "heartbeat").text();
    }

    @Override
    public void close() {
        if (closed.getAndSet(true))
            return;
        lastTimeMessageReceived = Long.MAX_VALUE;
        Closeable.closeQuietly(closable());
    }

    public void onMessageReceived() {
        lastTimeMessageReceived = System.currentTimeMillis();
    }


    private void initHeartbeatCheck() {

        self.set(() -> {
            System.out.println("checking heartbeat");
            if (!hasReceivedHeartbeat() && !closed.get()) {
                close();
            } else {

                HEARTBEAT_EXECUTOR.schedule(self.get(), heartbeatTimeoutMs, MILLISECONDS);
            }
        });
    }

    /**
     * periodically check that messages have been received, ie heartbeats
     */
    private void startHeartbeatCheck() {

        initHeartbeatCheck();
        if (hasHeartbeat.getAndSet(true))
            return;

        lastTimeMessageReceived = Long.MAX_VALUE;
        HEARTBEAT_EXECUTOR.schedule(self.get(), heartbeatTimeoutMs, MILLISECONDS);
    }

    /**
     * called periodically to check that the heartbeat has been received
     *
     * @return {@code true} if we have received a heartbeat recently
     */
    private boolean hasReceivedHeartbeat() {
        System.out.println("lastTimeMessageReceived=" + lastTimeMessageReceived + ", System" +
                ".currentTimeMillis()=" + System.currentTimeMillis() + ",heartbeatTimeoutMs=" + heartbeatTimeoutMs);

        return lastTimeMessageReceived > System.currentTimeMillis() - heartbeatTimeoutMs;
    }

}

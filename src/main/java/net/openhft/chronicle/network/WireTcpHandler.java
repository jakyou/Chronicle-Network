/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.network;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.util.Time;
import net.openhft.chronicle.network.api.TcpHandler;
import net.openhft.chronicle.network.api.session.SessionDetailsProvider;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.BufferOverflowException;
import java.util.function.Supplier;

public abstract class WireTcpHandler implements TcpHandler, Supplier<WireOutPublisher> {

    private static final int SIZE_OF_SIZE = 4;
    private static final Logger LOG = LoggerFactory.getLogger(WireTcpHandler.class);
    // this is the point at which it is worth doing more work to get more data.

    protected WireOutPublisher publisher;
    protected Wire outWire;
    private Wire inWire;
    private boolean recreateWire;
    private WireType wireType;

    public WireTcpHandler(@NotNull final NetworkContext nc) {
        this.wireType = nc.wireType();
        this.publisher = nc.wireOutPublisher();
    }

    public WireOutPublisher get() {
        return publisher;
    }


    @Override
    public void process(@NotNull Bytes in, @NotNull Bytes out) {

        checkWires(in, out, wireType());

        publisher.applyAction(outWire, () -> {
            if (in.readRemaining() >= SIZE_OF_SIZE && out.writePosition() < TcpEventHandler.TCP_BUFFER)
                read(in, out);
        });
    }

    @Override
    public void sendHeartBeat(Bytes out, SessionDetailsProvider sessionDetails) {
        if (out.writePosition() == 0) {
            final WireOut outWire = wireType.apply(out);
            outWire.writeDocument(true, w -> w.write(() -> "tid").int64(0));
            outWire.writeDocument(false, w -> w.writeEventName(() -> "heartbeat").int64(Time.currentTimeMillis()));
        }
    }

    @Override
    public void onEndOfConnection(boolean heartbeatTimeOut) {
        publisher.close();
    }

    /**
     * process all messages in this batch, provided there is plenty of output space.
     *
     * @param in  the source bytes
     * @param out the destination bytes
     * @return true if we can read attempt the next
     */
    private boolean read(@NotNull Bytes in, @NotNull Bytes out) {
        final long header = in.readInt(in.readPosition());
        long length = Wires.lengthOf(header);
        assert length >= 0 && length < 1 << 23 : "length=" + length + ",in=" + in + ", hex=" + in.toHexString();

        // we don't return on meta data of zero bytes as this is a system message
        if (length == 0 && Wires.isData(header)) {
            in.readSkip(SIZE_OF_SIZE);
            return false;
        }

        if (in.readRemaining() < length + SIZE_OF_SIZE) {
            // we have to first read more data before this can be processed
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("required length=%d but only got %d bytes, " +
                                "this is short by %d bytes", length, in.readRemaining(),
                        length - in.readRemaining()));
            return false;
        }

        long limit = in.readLimit();
        long end = in.readPosition() + length + SIZE_OF_SIZE;
        assert end <= limit;
        long outPos = out.writePosition();
        try {

            in.readLimit(end);

            final long position = inWire.bytes().readPosition();
            try {
                process(inWire, outWire);
            } finally {
                try {
                    inWire.bytes().readPosition(position + length);
                } catch (BufferOverflowException e) {
                    //noinspection ThrowFromFinallyBlock
                    throw new IllegalStateException("Unexpected error position: " + position + ", length: " + length + " limit(): " + inWire.bytes().readLimit(), e);
                }
            }

            long written = out.writePosition() - outPos;

            if (written > 0)
                return false;
        } catch (Throwable e) {
            LOG.error("", e);
        } finally {
            in.readLimit(limit);
            try {
                in.readPosition(end);
            } catch (Exception e) {
                throw new IllegalStateException("position: " + end
                        + ", limit:" + limit + ", readLimit: " + in.readLimit() + " " + in.toDebugString(), e);

            }
        }

        return true;
    }

    protected void checkWires(Bytes in, Bytes out, @NotNull WireType wireType) {
        if (recreateWire) {
            recreateWire = false;
            inWire = wireType.apply(in);
            outWire = wireType.apply(out);
            return;
        }

        if (inWire == null) {
            inWire = wireType.apply(in);
            recreateWire = false;
        }

        if (inWire.bytes() != in) {
            inWire = wireType.apply(in);
            recreateWire = false;
        }

        if ((outWire == null || outWire.bytes() != out)) {
            outWire = wireType.apply(out);
            recreateWire = false;
        }
    }


    /**
     * Process an incoming request
     */
    public WireType wireType() {
        return this.wireType;
    }

    /**
     * @param in  the wire to be processed
     * @param out the result of processing the {@code in}
     */
    protected abstract void process(@NotNull WireIn in,
                                    @NotNull WireOut out);

}

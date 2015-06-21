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
import net.openhft.chronicle.network.api.TcpHandler;
import net.openhft.chronicle.network.api.session.SessionDetailsProvider;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.Wires;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StreamCorruptedException;
import java.nio.BufferOverflowException;
import java.util.function.Function;

public abstract class WireTcpHandler implements TcpHandler {
    public static final int SIZE_OF_SIZE = 4;
    @NotNull
    private final Function<Bytes, Wire> bytesToWire;
    protected Wire inWire, outWire;

    private static final Logger LOG = LoggerFactory.getLogger(WireTcpHandler.class);

    public WireTcpHandler(@NotNull final Function<Bytes, Wire> bytesToWire) {
        this.bytesToWire = bytesToWire;
    }

    @Override
    public void process(@NotNull Bytes in, @NotNull Bytes out, @NotNull SessionDetailsProvider sessionDetails) {
        checkWires(in, out);

        if (in.remaining() < SIZE_OF_SIZE) {
            long outPos = out.position();

            publish(outWire);

            long written = out.position() - outPos;
            if (written == 0) {
                out.position(outPos);
                return;
            }
            assert written <= TcpEventHandler.CAPACITY;
            return;
        }

        do {
            if (!read(in, out, sessionDetails))
                return;
        } while (in.remaining() > SIZE_OF_SIZE && out.remaining() > out.capacity() / SIZE_OF_SIZE);
    }

    /**
     * process all messages in this batch, provided there is plenty of output space.
     *
     * @param in  the source bytes
     * @param out the destination bytes
     * @return true if we can read attempt the next
     */
    private boolean read(@NotNull Bytes in, @NotNull Bytes out, @NotNull SessionDetailsProvider sessionDetails) {
        final long header = in.readInt(in.position());
        long length = Wires.lengthOf(header);
        assert length >= 0 && length < 1 << 22 : "in=" + in + ", hex=" + in.toHexString();

        // we don't return on meta data of zero bytes as this is a system message
        if (length == 0 && Wires.isData(header)) {
            in.skip(SIZE_OF_SIZE);
            return false;
        }

        if (in.remaining() < length) {
            // we have to first read more data before this can be processed
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("required length=%d but only got %d bytes, " +
                                "this is short by %d bytes", length, in.remaining(),
                        length - in.remaining()));
            return false;
        }

        long limit = in.limit();
        long end = in.position() + length + SIZE_OF_SIZE;
        long outPos = out.position();
        try {

            in.limit(end);

            final long position = inWire.bytes().position();
            try {
                process(inWire, outWire, sessionDetails);
            } finally {
                try {
                    inWire.bytes().position(position + length);
                } catch (BufferOverflowException e) {
                    //noinspection ThrowFromFinallyBlock
                    throw new IllegalStateException("Unexpected error position: " + position + ", length: " + length + " limit(): " + inWire.bytes().limit(), e);
                }
            }

            long written = out.position() - outPos;

            if (written > 0)
                return false;
        } catch (Exception e) {
            LOG.error("", e);
        } finally {
            in.limit(limit);
            in.position(end);
        }

        return true;
    }

    private boolean recreateWire;

    private void checkWires(Bytes in, Bytes out) {
        if (recreateWire) {
            recreateWire = false;
            inWire = bytesToWire.apply(in);
            outWire = bytesToWire.apply(out);
            return;
        }

        if ((inWire == null || inWire.bytes() != in)) {
            inWire = bytesToWire.apply(in);
            recreateWire = false;
        }

        if ((outWire == null || outWire.bytes() != out)) {
            outWire = bytesToWire.apply(out);
            recreateWire = false;
        }
    }

    /**
     * Process an incoming request
     */

    /**
     * @param in  the wire to be processed
     * @param out the result of processing the {@code in}
     * @throws StreamCorruptedException if the wire is corrupt
     */
    protected abstract void process(@NotNull Wire in,
                                    @NotNull Wire out,
                                    @NotNull SessionDetailsProvider sessionDetails)
            throws StreamCorruptedException;

    /**
     * Publish some data
     */
    protected void publish(Wire out) {
    }
}
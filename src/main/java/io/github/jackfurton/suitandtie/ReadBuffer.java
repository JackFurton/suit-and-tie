package io.github.jackfurton.suitandtie;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** Bytes received from a client that haven't been parsed yet, between data().position() and limit(). */
final class ReadBuffer {

    static final int INITIAL_CAPACITY = 16 * 1024;
    static final int MAX_CAPACITY = 1 << 30;

    private ByteBuffer buf = ByteBuffer.allocate(INITIAL_CAPACITY).flip();

    ByteBuffer data() {
        return buf;
    }

    int readFrom(InputStream in) throws IOException {
        makeRoom();
        int n = in.read(buf.array(), buf.limit(), buf.capacity() - buf.limit());
        if (n > 0) {
            buf.limit(buf.limit() + n);
        }
        return n;
    }

    int readFrom(ReadableByteChannel channel) throws IOException {
        makeRoom();
        int unparsed = buf.position();
        buf.position(buf.limit()).limit(buf.capacity());
        int n = channel.read(buf);
        buf.limit(buf.position()).position(unparsed);
        return n;
    }

    private void makeRoom() throws ProtocolException {
        if (!buf.hasRemaining()) {
            buf.position(0).limit(0);
        }
        if (buf.limit() < buf.capacity()) {
            return;
        }
        if (buf.position() > 0) {
            buf.compact().flip();
            return;
        }
        // Full of one unfinished command, e.g. a large SET value still arriving.
        if (buf.capacity() == MAX_CAPACITY) {
            throw new ProtocolException("command exceeds " + MAX_CAPACITY + " bytes");
        }
        ByteBuffer bigger = ByteBuffer.allocate((int) Math.min(2L * buf.capacity(), MAX_CAPACITY));
        buf = bigger.put(buf).flip();
    }
}

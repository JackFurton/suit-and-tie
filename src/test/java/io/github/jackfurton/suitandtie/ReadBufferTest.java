package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReadBufferTest {

    @Test
    void growsToHoldOneLargeUnparsedCommand() throws IOException {
        byte[] payload = new byte[ReadBuffer.INITIAL_CAPACITY * 5 + 3];
        Arrays.fill(payload, (byte) 'x');
        var in = new ByteArrayInputStream(payload);
        var buffer = new ReadBuffer();
        while (buffer.readFrom(in) != -1) {
            // Nothing is consumed, as if the parser kept saying the command isn't complete yet.
        }
        ByteBuffer data = buffer.data();
        byte[] got = new byte[data.remaining()];
        data.get(got);
        assertArrayEquals(payload, got);
    }

    @Test
    void keepsUnconsumedBytesWhenCompacting() throws IOException {
        byte[] payload = new byte[ReadBuffer.INITIAL_CAPACITY + 100];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        var in = new ByteArrayInputStream(payload);
        var buffer = new ReadBuffer();
        buffer.readFrom(in);
        assertEquals(ReadBuffer.INITIAL_CAPACITY, buffer.data().remaining());
        buffer.data().position(ReadBuffer.INITIAL_CAPACITY - 10);
        buffer.readFrom(in);
        ByteBuffer data = buffer.data();
        assertEquals(110, data.remaining());
        for (int i = 0; i < 110; i++) {
            assertEquals(payload[ReadBuffer.INITIAL_CAPACITY - 10 + i], data.get());
        }
    }
}

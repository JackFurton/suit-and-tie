package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespReaderTest {

    private static RespReader reader(String wire) {
        return new RespReader(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void readsArrayOfBulkStrings() throws IOException {
        List<byte[]> args = reader("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n").readCommand().orElseThrow();
        assertEquals(List.of("SET", "foo", "bar"), args.stream().map(RespReaderTest::text).toList());
    }

    @Test
    void readsBackToBackCommands() throws IOException {
        RespReader reader = reader("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n");
        assertTrue(reader.readCommand().isPresent());
        assertTrue(reader.readCommand().isPresent());
        assertTrue(reader.readCommand().isEmpty());
    }

    @Test
    void valuesAreBinarySafe() throws IOException {
        List<byte[]> args = reader("*1\r\n$4\r\na\r\nb\r\n").readCommand().orElseThrow();
        assertArrayEquals(new byte[] {'a', '\r', '\n', 'b'}, args.getFirst());
    }

    @Test
    void readsEmptyBulkString() throws IOException {
        List<byte[]> args = reader("*1\r\n$0\r\n\r\n").readCommand().orElseThrow();
        assertEquals(0, args.getFirst().length);
    }

    @Test
    void emptyAtCleanEof() throws IOException {
        assertTrue(reader("").readCommand().isEmpty());
    }

    @Test
    void eofMidCommandThrows() {
        assertThrows(EOFException.class, () -> reader("*2\r\n$3\r\nGET\r\n").readCommand());
        assertThrows(EOFException.class, () -> reader("*1\r\n$10\r\nshort\r\n").readCommand());
    }

    @Test
    void readsInlineCommands() throws IOException {
        List<byte[]> args = reader("SET  foo\tbar\r\n").readCommand().orElseThrow();
        assertEquals(List.of("SET", "foo", "bar"), args.stream().map(RespReaderTest::text).toList());
    }

    @Test
    void inlineAcceptsBareNewlineAndSkipsBlankLines() throws IOException {
        RespReader reader = reader("\r\n   \nPING\n");
        assertEquals("PING", text(reader.readCommand().orElseThrow().getFirst()));
        assertTrue(reader.readCommand().isEmpty());
    }

    @Test
    void rejectsOversizedInline() {
        assertThrows(ProtocolException.class, () -> reader("x".repeat(RespReader.MAX_INLINE_LENGTH + 1)).readCommand());
    }

    @Test
    void rejectsBadLengths() {
        assertThrows(ProtocolException.class, () -> reader("*-1\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*1x\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*99999999999999999999\r\n").readCommand());
    }

    @Test
    void rejectsMissingTerminator() {
        assertThrows(ProtocolException.class, () -> reader("*1\r\n$3\r\nfooXY").readCommand());
    }
}

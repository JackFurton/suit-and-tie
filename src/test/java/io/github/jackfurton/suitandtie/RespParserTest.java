package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RespParserTest {

    private static ByteBuffer wire(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> parse(ByteBuffer buf) throws ProtocolException {
        List<byte[]> args = RespParser.parse(buf);
        return args == null ? null : args.stream().map(a -> new String(a, StandardCharsets.UTF_8)).toList();
    }

    @Test
    void readsArrayOfBulkStrings() throws ProtocolException {
        assertEquals(List.of("SET", "foo", "bar"), parse(wire("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n")));
    }

    // The event loop calls parse() with whatever has arrived so far, so every prefix of a valid command
    // must come back "not yet" and leave the buffer untouched for the next attempt.
    @ParameterizedTest
    @ValueSource(strings = {"*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n", "SET foo bar\r\n", "*1\r\n$0\r\n\r\n"})
    void everyPrefixIsIncomplete(String command) throws ProtocolException {
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        for (int len = 0; len < bytes.length; len++) {
            ByteBuffer buf = ByteBuffer.wrap(bytes, 0, len);
            assertNull(RespParser.parse(buf), "prefix of length " + len);
            assertEquals(0, buf.position(), "position after prefix of length " + len);
        }
        ByteBuffer whole = ByteBuffer.wrap(bytes);
        RespParser.parse(whole);
        assertEquals(bytes.length, whole.position());
    }

    @Test
    void readsBackToBackCommands() throws ProtocolException {
        ByteBuffer buf = wire("*1\r\n$4\r\nPING\r\nECHO hi\r\n*1\r\n$4\r\nPI");
        assertEquals(List.of("PING"), parse(buf));
        assertEquals(List.of("ECHO", "hi"), parse(buf));
        assertNull(parse(buf));
        assertEquals("*1\r\n$4\r\nPI".length(), buf.remaining());
    }

    @Test
    void valuesAreBinarySafe() throws ProtocolException {
        assertArrayEquals(new byte[] {'a', '\r', '\n', 'b'}, RespParser.parse(wire("*1\r\n$4\r\na\r\nb\r\n")).getFirst());
    }

    @Test
    void readsEmptyBulkString() throws ProtocolException {
        assertEquals(List.of(""), parse(wire("*1\r\n$0\r\n\r\n")));
    }

    @Test
    void readsInlineCommands() throws ProtocolException {
        assertEquals(List.of("SET", "foo", "bar"), parse(wire("SET  foo\tbar\r\n")));
    }

    @Test
    void skipsBlankLinesAndAcceptsBareNewline() throws ProtocolException {
        ByteBuffer buf = wire("\r\n   \nPING\n");
        assertEquals(List.of("PING"), parse(buf));
        assertNull(parse(buf));
        assertEquals(0, buf.remaining());
    }

    @Test
    void rejectsOversizedInline() {
        assertThrows(ProtocolException.class, () -> parse(wire("x".repeat(RespParser.MAX_INLINE_LENGTH + 1))));
    }

    @Test
    void rejectsBadLengths() {
        assertThrows(ProtocolException.class, () -> parse(wire("*-1\r\n")));
        assertThrows(ProtocolException.class, () -> parse(wire("*\r\n")));
        assertThrows(ProtocolException.class, () -> parse(wire("*1x\r\n")));
        assertThrows(ProtocolException.class, () -> parse(wire("*99999999999999999999\r\n")));
        assertThrows(ProtocolException.class, () -> parse(wire("*1\r\n$600000000\r\n")));
    }

    @Test
    void rejectsMalformedFraming() {
        assertThrows(ProtocolException.class, () -> parse(wire("*1\r\n+PING\r\n")));
        assertThrows(ProtocolException.class, () -> parse(wire("*1\r\n$3\r\nfooXY")));
        assertThrows(ProtocolException.class, () -> parse(wire("*1\rX")));
    }
}

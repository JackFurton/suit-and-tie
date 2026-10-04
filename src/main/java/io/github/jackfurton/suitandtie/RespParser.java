package io.github.jackfurton.suitandtie;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RespParser {

    // Same limits as Redis. Without them a client sending "*2147483647" makes us allocate gigabytes.
    static final int MAX_ARGS = 1024 * 1024;
    static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    static final int MAX_INLINE_LENGTH = 64 * 1024;

    private static final int INCOMPLETE = -1;

    private RespParser() {}

    /**
     * Parses one command starting at the buffer's position and advances past it. Returns null, with the
     * position unchanged, when the buffer doesn't hold a whole command yet.
     */
    static List<byte[]> parse(ByteBuffer buf) throws ProtocolException {
        while (buf.hasRemaining()) {
            int start = buf.position();
            List<byte[]> command = buf.get(start) == '*' ? parseArray(buf) : parseInline(buf);
            if (command == null) {
                buf.position(start);
                return null;
            }
            // Redis ignores blank lines, so hitting enter in telnet doesn't produce an error.
            if (!command.isEmpty()) {
                return command;
            }
        }
        return null;
    }

    private static List<byte[]> parseArray(ByteBuffer buf) throws ProtocolException {
        buf.get();
        int count = readLength(buf, MAX_ARGS);
        if (count == INCOMPLETE) {
            return null;
        }
        // Capped because we may be called many times while a large command trickles in.
        List<byte[]> args = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            if (!buf.hasRemaining()) {
                return null;
            }
            byte prefix = buf.get();
            if (prefix != '$') {
                throw new ProtocolException("expected '$', got '" + (char) prefix + "'");
            }
            int length = readLength(buf, MAX_BULK_LENGTH);
            if (length == INCOMPLETE || buf.remaining() < length + 2) {
                return null;
            }
            byte[] value = new byte[length];
            buf.get(value);
            if (buf.get() != '\r' || buf.get() != '\n') {
                throw new ProtocolException("expected CRLF after bulk string");
            }
            args.add(value);
        }
        return args;
    }

    private static int readLength(ByteBuffer buf, int max) throws ProtocolException {
        long value = 0;
        int digits = 0;
        while (buf.hasRemaining()) {
            byte b = buf.get();
            if (b == '\r') {
                if (!buf.hasRemaining()) {
                    return INCOMPLETE;
                }
                if (buf.get() != '\n') {
                    throw new ProtocolException("expected LF after CR");
                }
                if (digits == 0) {
                    throw new ProtocolException("empty length");
                }
                return (int) value;
            }
            if (b < '0' || b > '9') {
                throw new ProtocolException("invalid length byte '" + (char) b + "'");
            }
            value = value * 10 + (b - '0');
            digits++;
            if (value > max) {
                throw new ProtocolException("length exceeds " + max);
            }
        }
        return INCOMPLETE;
    }

    // Plain "SET foo bar\r\n", what telnet and nc send. Quoted arguments aren't supported.
    private static List<byte[]> parseInline(ByteBuffer buf) throws ProtocolException {
        int start = buf.position();
        int end = start;
        while (end < buf.limit() && buf.get(end) != '\n') {
            end++;
        }
        if (end - start > MAX_INLINE_LENGTH) {
            throw new ProtocolException("inline command exceeds " + MAX_INLINE_LENGTH + " bytes");
        }
        if (end == buf.limit()) {
            return null;
        }
        byte[] line = new byte[end - start];
        buf.get(line);
        buf.get();
        String text = new String(line, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(text.split("[ \\t]+")).map(arg -> arg.getBytes(StandardCharsets.UTF_8)).toList();
    }
}

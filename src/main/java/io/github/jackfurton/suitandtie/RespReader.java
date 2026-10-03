package io.github.jackfurton.suitandtie;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class RespReader {

    // Same limits as Redis. Without them a client sending "*2147483647" makes us allocate gigabytes.
    static final int MAX_ARGS = 1024 * 1024;
    static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    static final int MAX_INLINE_LENGTH = 64 * 1024;

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in;
    }

    /** Empty when the client hung up cleanly between commands. */
    public Optional<List<byte[]>> readCommand() throws IOException {
        while (true) {
            int first = in.read();
            if (first == -1) {
                return Optional.empty();
            }
            if (first == '*') {
                return Optional.of(readArray());
            }
            List<byte[]> inline = readInline(first);
            // Redis ignores blank lines, so hitting enter in telnet doesn't produce an error.
            if (!inline.isEmpty()) {
                return Optional.of(inline);
            }
        }
    }

    private List<byte[]> readArray() throws IOException {
        int count = readLength(MAX_ARGS);
        List<byte[]> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            args.add(readBulkString());
        }
        return args;
    }

    // Plain "SET foo bar\r\n", what telnet and nc send. Quoted arguments aren't supported.
    private List<byte[]> readInline(int first) throws IOException {
        var line = new ByteArrayOutputStream();
        int b = first;
        while (b != '\n') {
            if (line.size() >= MAX_INLINE_LENGTH) {
                throw new ProtocolException("inline command exceeds " + MAX_INLINE_LENGTH + " bytes");
            }
            line.write(b);
            b = readByte();
        }
        String text = line.toString(StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(text.split("[ \t]+")).map(arg -> arg.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private byte[] readBulkString() throws IOException {
        expect('$');
        int length = readLength(MAX_BULK_LENGTH);
        byte[] value = in.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("connection closed mid-value");
        }
        expect('\r');
        expect('\n');
        return value;
    }

    private int readLength(int max) throws IOException {
        long value = 0;
        int digits = 0;
        int b;
        while ((b = readByte()) != '\r') {
            if (b < '0' || b > '9') {
                throw new ProtocolException("invalid length byte '" + (char) b + "'");
            }
            value = value * 10 + (b - '0');
            digits++;
            if (value > max) {
                throw new ProtocolException("length exceeds " + max);
            }
        }
        expect('\n');
        if (digits == 0) {
            throw new ProtocolException("empty length");
        }
        return (int) value;
    }

    private void expect(char expected) throws IOException {
        int b = readByte();
        if (b != expected) {
            throw new ProtocolException("expected '" + expected + "', got '" + (char) b + "'");
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("connection closed mid-command");
        }
        return b;
    }
}

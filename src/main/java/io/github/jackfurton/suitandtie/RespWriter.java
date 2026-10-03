package io.github.jackfurton.suitandtie;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out;
    }

    public void write(Reply reply) throws IOException {
        switch (reply) {
            case Reply.Simple(String value) -> line('+', value);
            case Reply.Error(String message) -> line('-', message);
            case Reply.Bulk(byte[] value) -> {
                line('$', Integer.toString(value.length));
                out.write(value);
                out.write(CRLF);
            }
            case Reply.Nil() -> line('$', "-1");
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    private void line(char prefix, String text) throws IOException {
        out.write(prefix);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}

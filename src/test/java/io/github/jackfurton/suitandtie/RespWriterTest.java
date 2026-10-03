package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespWriterTest {

    private static String encode(Reply reply) throws IOException {
        var out = new ByteArrayOutputStream();
        new RespWriter(out).write(reply);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void encodesEachReplyType() throws IOException {
        assertEquals("+OK\r\n", encode(new Reply.Simple("OK")));
        assertEquals("-ERR nope\r\n", encode(new Reply.Error("ERR nope")));
        assertEquals("$5\r\nhello\r\n", encode(new Reply.Bulk("hello".getBytes(StandardCharsets.UTF_8))));
        assertEquals("$0\r\n\r\n", encode(new Reply.Bulk(new byte[0])));
        assertEquals("$-1\r\n", encode(new Reply.Nil()));
    }

    @Test
    void bulkLengthCountsBytesNotChars() throws IOException {
        assertEquals("$2\r\né\r\n", encode(new Reply.Bulk("é".getBytes(StandardCharsets.UTF_8))));
    }
}

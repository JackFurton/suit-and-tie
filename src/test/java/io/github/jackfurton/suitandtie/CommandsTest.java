package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandsTest {

    private final Commands commands = new Commands();

    private Reply run(String... args) {
        return commands.execute(Arrays.stream(args).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList());
    }

    private static void assertBulk(String expected, Reply reply) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), assertInstanceOf(Reply.Bulk.class, reply).value());
    }

    @Test
    void ping() {
        assertEquals(new Reply.Simple("PONG"), run("PING"));
        assertBulk("hi", run("PING", "hi"));
    }

    @Test
    void echo() {
        assertBulk("hello", run("ECHO", "hello"));
    }

    @Test
    void setThenGet() {
        assertEquals(new Reply.Simple("OK"), run("SET", "k", "v1"));
        assertBulk("v1", run("GET", "k"));
        run("SET", "k", "v2");
        assertBulk("v2", run("GET", "k"));
    }

    @Test
    void getMissingKeyIsNil() {
        assertEquals(new Reply.Nil(), run("GET", "nope"));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        run("set", "k", "v");
        assertBulk("v", run("gEt", "k"));
    }

    @Test
    void keysCompareByContentNotIdentity() {
        commands.execute(List.of("SET".getBytes(StandardCharsets.UTF_8), new byte[] {1, 2}, new byte[] {3}));
        Reply reply = commands.execute(List.of("GET".getBytes(StandardCharsets.UTF_8), new byte[] {1, 2}));
        assertArrayEquals(new byte[] {3}, assertInstanceOf(Reply.Bulk.class, reply).value());
    }

    @Test
    void wrongArity() {
        assertEquals(new Reply.Error("ERR wrong number of arguments for 'get' command"), run("GET"));
        assertEquals(new Reply.Error("ERR wrong number of arguments for 'set' command"), run("SET", "k"));
    }

    @Test
    void unknownCommand() {
        assertEquals(new Reply.Error("ERR unknown command 'flushall'"), run("FLUSHALL"));
    }
}

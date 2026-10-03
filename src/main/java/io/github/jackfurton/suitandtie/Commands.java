package io.github.jackfurton.suitandtie;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class Commands {

    private final BytesMap<byte[]> store = new BytesMap<>();

    Reply execute(List<byte[]> args) {
        if (args.isEmpty()) {
            return new Reply.Error("ERR empty command");
        }
        // Locale.ROOT: in a Turkish locale "ping".toUpperCase() is "PİNG" and nothing would match.
        String name = new String(args.getFirst(), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        return switch (name) {
            case "PING" -> ping(args);
            case "ECHO" -> echo(args);
            case "GET" -> get(args);
            case "SET" -> set(args);
            default -> new Reply.Error("ERR unknown command '" + name.toLowerCase(Locale.ROOT) + "'");
        };
    }

    private Reply ping(List<byte[]> args) {
        return switch (args.size()) {
            case 1 -> new Reply.Simple("PONG");
            case 2 -> new Reply.Bulk(args.get(1));
            default -> wrongArity("ping");
        };
    }

    private Reply echo(List<byte[]> args) {
        if (args.size() != 2) {
            return wrongArity("echo");
        }
        return new Reply.Bulk(args.get(1));
    }

    private Reply get(List<byte[]> args) {
        if (args.size() != 2) {
            return wrongArity("get");
        }
        byte[] value = store.get(args.get(1));
        return value == null ? new Reply.Nil() : new Reply.Bulk(value);
    }

    private Reply set(List<byte[]> args) {
        if (args.size() != 3) {
            return wrongArity("set");
        }
        store.put(args.get(1), args.get(2));
        return new Reply.Simple("OK");
    }

    private static Reply wrongArity(String command) {
        return new Reply.Error("ERR wrong number of arguments for '" + command + "' command");
    }
}

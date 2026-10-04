package io.github.jackfurton.suitandtie;

import java.io.IOException;
import java.util.Locale;

public class Main {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6379;
        ServerMode mode = args.length > 1 ? ServerMode.valueOf(args[1].toUpperCase(Locale.ROOT)) : ServerMode.EVENTLOOP;
        try (Server server = mode.open(port)) {
            System.out.println("listening on " + server.port() + " (" + mode.name().toLowerCase(Locale.ROOT) + ")");
            server.serve();
        }
    }
}

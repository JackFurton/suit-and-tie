package io.github.jackfurton.suitandtie;

public sealed interface Reply {

    record Simple(String value) implements Reply {}

    record Error(String message) implements Reply {}

    record Bulk(byte[] value) implements Reply {}

    record Nil() implements Reply {}
}

package io.github.jackfurton.suitandtie;

import java.util.Arrays;

// Arrays compare by identity, so a raw byte[] can't be a HashMap key: two equal keys would never match.
record Key(byte[] bytes) {

    @Override
    public boolean equals(Object o) {
        return o instanceof Key other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return Arrays.toString(bytes);
    }
}

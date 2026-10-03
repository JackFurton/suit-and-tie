package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class BytesMapTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetRemove() {
        var map = new BytesMap<String>();
        assertNull(map.put(b("a"), "1"));
        assertEquals("1", map.get(b("a")));
        assertEquals("1", map.put(b("a"), "2"));
        assertEquals("2", map.get(b("a")));
        assertEquals(1, map.size());
        assertEquals("2", map.remove(b("a")));
        assertNull(map.get(b("a")));
        assertNull(map.remove(b("a")));
        assertEquals(0, map.size());
    }

    @Test
    void keysCompareByContent() {
        var map = new BytesMap<String>();
        map.put(new byte[] {1, 2, 3}, "x");
        assertTrue(map.containsKey(new byte[] {1, 2, 3}));
        assertFalse(map.containsKey(new byte[] {1, 2}));
    }

    @Test
    void emptyKeyIsValid() {
        var map = new BytesMap<String>();
        map.put(new byte[0], "empty");
        assertEquals("empty", map.get(new byte[0]));
    }

    @Test
    void nullKeyRejected() {
        assertThrows(NullPointerException.class, () -> new BytesMap<String>().get(null));
    }

    @Test
    void growsPastInitialCapacity() {
        var map = new BytesMap<Integer>();
        for (int i = 0; i < 100_000; i++) {
            map.put(b("key:" + i), i);
        }
        assertEquals(100_000, map.size());
        for (int i = 0; i < 100_000; i++) {
            assertEquals(i, map.get(b("key:" + i)));
        }
    }

    // Every key hashes to the last slot, so probe chains wrap around the end of the table and every
    // remove has to backward-shift the whole cluster.
    @Test
    void survivesTotalCollisionsAndWraparound() {
        ToIntFunction<byte[]> lastSlot = key -> 15;
        differential(new BytesMap<>(16, lastSlot), 3_000, 20, 1);
    }

    @Test
    void matchesHashMapUnderRandomOperations() {
        differential(new BytesMap<>(), 500_000, 2_000, 2);
    }

    @Test
    void matchesHashMapWithClusteredHashes() {
        // Few distinct hash values: long Robin Hood displacement chains without full collision.
        ToIntFunction<byte[]> coarse = key -> BytesMap.seededHasher(7).applyAsInt(key) & 0x3f;
        differential(new BytesMap<>(16, coarse), 200_000, 1_000, 3);
    }

    // Applies the same random puts and removes to our map and java.util.HashMap and checks they agree on
    // every return value. A small key space forces lots of overwrites and removes of present keys.
    private static void differential(BytesMap<Integer> map, int ops, int keySpace, long seed) {
        Map<String, Integer> reference = new HashMap<>();
        Random random = new Random(seed);
        for (int i = 0; i < ops; i++) {
            String key = Integer.toString(random.nextInt(keySpace));
            switch (random.nextInt(3)) {
                case 0 -> assertEquals(reference.put(key, i), map.put(b(key), i), "put " + key);
                case 1 -> assertEquals(reference.remove(key), map.remove(b(key)), "remove " + key);
                default -> assertEquals(reference.get(key), map.get(b(key)), "get " + key);
            }
            assertEquals(reference.size(), map.size());
        }
        for (int k = 0; k < keySpace; k++) {
            String key = Integer.toString(k);
            assertEquals(reference.get(key), map.get(b(key)), "final " + key);
        }
    }
}

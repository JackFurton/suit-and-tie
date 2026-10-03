package io.github.jackfurton.suitandtie;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

/**
 * Hash map from byte[] keys (compared by content) to values, using open addressing with Robin Hood
 * probing. Not thread-safe.
 */
public final class BytesMap<V> {

    private static final int MIN_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;
    private static final double LOAD_FACTOR = 0.75;

    private final ToIntFunction<byte[]> hasher;

    // Parallel arrays instead of an array of Entry objects: one slot is three array reads, not a pointer
    // chase to a separate object somewhere else on the heap.
    private int[] hashes;
    private byte[][] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int resizeAt;

    public BytesMap() {
        this(MIN_CAPACITY, seededHasher(ThreadLocalRandom.current().nextInt()));
    }

    BytesMap(int initialCapacity, ToIntFunction<byte[]> hasher) {
        this.hasher = hasher;
        allocate(Math.max(MIN_CAPACITY, Integer.highestOneBit(initialCapacity - 1) << 1));
    }

    public int size() {
        return size;
    }

    public V get(byte[] key) {
        int slot = find(key, hash(key));
        return slot < 0 ? null : value(slot);
    }

    public boolean containsKey(byte[] key) {
        return find(key, hash(key)) >= 0;
    }

    public V put(byte[] key, V value) {
        int hash = hash(key);
        int slot = find(key, hash);
        if (slot >= 0) {
            V previous = value(slot);
            values[slot] = value;
            return previous;
        }
        if (size >= resizeAt) {
            grow();
        }
        insertNew(hash, key, value);
        size++;
        return null;
    }

    public V remove(byte[] key) {
        int slot = find(key, hash(key));
        if (slot < 0) {
            return null;
        }
        V previous = value(slot);
        // Backward shift: pull each following displaced entry one slot closer to home, so lookups never
        // stop early at a hole. This is what lets us skip tombstones entirely.
        int next = (slot + 1) & mask;
        while (keys[next] != null && distance(hashes[next], next) > 0) {
            hashes[slot] = hashes[next];
            keys[slot] = keys[next];
            values[slot] = values[next];
            slot = next;
            next = (next + 1) & mask;
        }
        keys[slot] = null;
        values[slot] = null;
        size--;
        return previous;
    }

    private int hash(byte[] key) {
        return hasher.applyAsInt(Objects.requireNonNull(key, "key"));
    }

    private int find(byte[] key, int hash) {
        int slot = hash & mask;
        for (int dist = 0; ; dist++) {
            byte[] candidate = keys[slot];
            if (candidate == null) {
                return -1;
            }
            // If our key were here, Robin Hood insertion would have placed it before any entry that is
            // closer to its home slot than we are to ours. So once we pass one, we can stop.
            if (distance(hashes[slot], slot) < dist) {
                return -1;
            }
            if (hashes[slot] == hash && Arrays.equals(candidate, key)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    // Robin Hood: an entry far from home takes the slot from one that is closer to home ("rich"), and
    // the evicted entry keeps probing. This keeps every probe sequence short and roughly equal.
    private void insertNew(int hash, byte[] key, Object value) {
        int slot = hash & mask;
        int dist = 0;
        while (keys[slot] != null) {
            int residentDist = distance(hashes[slot], slot);
            if (residentDist < dist) {
                int h = hashes[slot];
                byte[] k = keys[slot];
                Object v = values[slot];
                hashes[slot] = hash;
                keys[slot] = key;
                values[slot] = value;
                hash = h;
                key = k;
                value = v;
                dist = residentDist;
            }
            slot = (slot + 1) & mask;
            dist++;
        }
        hashes[slot] = hash;
        keys[slot] = key;
        values[slot] = value;
    }

    private int distance(int hash, int slot) {
        return (slot - (hash & mask)) & mask;
    }

    private void grow() {
        if (keys.length == MAX_CAPACITY) {
            throw new IllegalStateException("BytesMap is full");
        }
        int[] oldHashes = hashes;
        byte[][] oldKeys = keys;
        Object[] oldValues = values;
        allocate(keys.length * 2);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != null) {
                insertNew(oldHashes[i], oldKeys[i], oldValues[i]);
            }
        }
    }

    private void allocate(int capacity) {
        hashes = new int[capacity];
        keys = new byte[capacity][];
        values = new Object[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * LOAD_FACTOR);
    }

    @SuppressWarnings("unchecked")
    private V value(int slot) {
        return (V) values[slot];
    }

    // FNV-1a over the bytes, then murmur3's finalizer. FNV alone leaves the low bits poorly mixed, and the
    // low bits are exactly what "hash & mask" uses. The random seed stops a client from precomputing keys
    // that all land in one slot, though unlike Redis's SipHash it isn't proven against that.
    static ToIntFunction<byte[]> seededHasher(int seed) {
        return key -> {
            int h = seed ^ 0x811c9dc5;
            for (byte b : key) {
                h = (h ^ (b & 0xff)) * 0x01000193;
            }
            h ^= key.length;
            h ^= h >>> 16;
            h *= 0x85ebca6b;
            h ^= h >>> 13;
            h *= 0xc2b2ae35;
            h ^= h >>> 16;
            return h;
        };
    }
}

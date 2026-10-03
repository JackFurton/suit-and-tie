package io.github.jackfurton.suitandtie;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

// BytesMap<byte[]> against HashMap<Key, byte[]>, the Key-wrapper setup it replaced in Commands.
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MapBenchmark {

    // 1k fits in L1/L2 cache, 1M does not: the gap between them is the cost of going to RAM.
    @Param({"1000", "1000000"})
    int size;

    private byte[][] present;
    private byte[][] absent;
    private BytesMap<byte[]> bytesMap;
    private HashMap<Key, byte[]> hashMap;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        present = new byte[size][];
        absent = new byte[size][];
        bytesMap = new BytesMap<>();
        hashMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            present[i] = ("key:" + i).getBytes(StandardCharsets.UTF_8);
            absent[i] = ("missing:" + i).getBytes(StandardCharsets.UTF_8);
            bytesMap.put(present[i], present[i]);
            hashMap.put(new Key(present[i]), present[i]);
        }
        // Shuffled so lookups jump around memory like real traffic instead of walking it in insert order.
        shuffle(present);
        shuffle(absent);
    }

    private static void shuffle(byte[][] array) {
        Random random = new Random(42);
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            byte[] tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    // Each call copies the key, as Commands does when bytes arrive off the socket: the map must hash and
    // compare content, not reuse a cached hash or win an identity check.
    private byte[] next(byte[][] keys) {
        int i = cursor;
        cursor = i + 1 == keys.length ? 0 : i + 1;
        return keys[i].clone();
    }

    @Benchmark
    public byte[] bytesMapGetHit() {
        return bytesMap.get(next(present));
    }

    @Benchmark
    public byte[] hashMapGetHit() {
        return hashMap.get(new Key(next(present)));
    }

    @Benchmark
    public byte[] bytesMapGetMiss() {
        return bytesMap.get(next(absent));
    }

    @Benchmark
    public byte[] hashMapGetMiss() {
        return hashMap.get(new Key(next(absent)));
    }

    @Benchmark
    public byte[] bytesMapOverwrite() {
        byte[] key = next(present);
        return bytesMap.put(key, key);
    }

    @Benchmark
    public byte[] hashMapOverwrite() {
        byte[] key = next(present);
        return hashMap.put(new Key(key), key);
    }
}

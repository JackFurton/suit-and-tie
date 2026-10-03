package io.github.jackfurton.suitandtie;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

// Inserting into an empty map, including every resize along the way.
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FillBenchmark {

    @Param({"1000000"})
    int size;

    // Sequential keys like "key:1", "key:2" get near-sequential Arrays.hashCode values, so HashMap writes
    // to neighbouring buckets and stays in cache. It fills twice as fast that way, which real traffic won't give it.
    @Param({"sequential", "shuffled"})
    String order;

    private byte[][] keys;

    @Setup
    public void setup() {
        keys = new byte[size][];
        for (int i = 0; i < size; i++) {
            keys[i] = ("key:" + i).getBytes(StandardCharsets.UTF_8);
        }
        if (order.equals("shuffled")) {
            Random random = new Random(42);
            for (int i = keys.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                byte[] tmp = keys[i];
                keys[i] = keys[j];
                keys[j] = tmp;
            }
        }
    }

    @Benchmark
    public BytesMap<byte[]> bytesMap() {
        var map = new BytesMap<byte[]>();
        for (byte[] key : keys) {
            map.put(key, key);
        }
        return map;
    }

    @Benchmark
    public HashMap<Key, byte[]> hashMap() {
        var map = new HashMap<Key, byte[]>();
        for (byte[] key : keys) {
            map.put(new Key(key), key);
        }
        return map;
    }
}

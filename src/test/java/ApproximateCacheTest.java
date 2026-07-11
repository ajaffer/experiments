import org.experiments.cache.RedisSampledLRUCache;
import org.experiments.cache.StripedLRUCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approximate caches can't be tested by exact eviction order (that's the point of them),
 * so these tests assert invariants and statistical properties instead: size stays bounded,
 * nothing corrupts under concurrency, and recently-used keys mostly survive.
 */
public class ApproximateCacheTest {

    // ---- invariants ----

    @Test
    void sampledCacheStaysWithinCapacityAfterConcurrentStorm() throws InterruptedException {
        int capacity = 100;
        int threads = 16;
        var cache = new RedisSampledLRUCache<Integer, Integer>(capacity);

        List<Future<?>> results = CacheConcurrency.runConcurrently(threads, () -> {
            for (int k = 0; k < 5_000; k++) cache.put(k, k);
        });

        for (Future<?> r : results) assertDoesNotThrow(() -> r.get());
        assertTrue(cache.size() <= capacity, "size " + cache.size() + " exceeded capacity " + capacity);
    }

    @Test
    void stripedCacheStaysWithinEffectiveCapacityAfterConcurrentStorm() throws InterruptedException {
        int capacity = 64;
        int shards = 8;                              // 64 / 8 = 8 per shard, effective capacity == 64
        int threads = 16;
        var cache = new StripedLRUCache<Integer, Integer>(capacity, shards);

        List<Future<?>> results = CacheConcurrency.runConcurrently(threads, () -> {
            for (int k = 0; k < 5_000; k++) cache.put(k, k);
        });

        for (Future<?> r : results) assertDoesNotThrow(() -> r.get());
        assertTrue(cache.size() <= capacity, "size " + cache.size() + " exceeded effective capacity " + capacity);
    }

    @Test
    void concurrentEvictionDoesNotCorrupt() throws InterruptedException {
        int threads = 16;
        int opsPerThread = 5_000;
        var sampled = new RedisSampledLRUCache<Integer, Integer>(50);
        var striped = new StripedLRUCache<Integer, Integer>(50);

        List<Future<?>> results = CacheConcurrency.runConcurrently(threads, () -> {
            int x = 1;                              // per-thread LCG; no shared RNG
            for (int i = 0; i < opsPerThread; i++) {
                x = x * 1103515245 + 12345;
                int key = Math.floorMod(x, 200);
                if ((i & 1) == 0) {
                    sampled.put(key, key);
                    striped.put(key, key);
                } else {
                    sampled.get(key);
                    striped.get(key);
                }
            }
        });

        for (Future<?> r : results) assertDoesNotThrow(() -> r.get());
        sampled.put(999, 999);
        striped.put(999, 999);
        assertEquals(999, sampled.get(999));
        assertEquals(999, striped.get(999));
    }

    // ---- statistics: recency actually protects hot keys ----

    @Test
    void recentlyUsedKeysMostlySurvive() {
        int survivors = hotSetSurvivors(10);        // Redis-quality sample size
        assertTrue(survivors >= 9, "expected the hot set to mostly survive, but only " + survivors + "/10 did");
    }

    @Test
    void largerSampleSizeRetainsHotSetAtLeastAsWell() {
        int withSmallSample = hotSetSurvivors(1);   // sample of 1 ≈ random eviction, ignores recency
        int withLargeSample = hotSetSurvivors(10);  // picks the oldest of 10, so recency is respected
        assertTrue(withLargeSample >= withSmallSample,
                "sample=10 retained " + withLargeSample + " hot keys, sample=1 retained " + withSmallSample);
    }

    /**
     * Primes a hot set of 10 keys, then hammers the cache with cold keys while repeatedly
     * touching the hot ones, and returns how many hot keys survived eviction. Deterministic
     * (no RNG), so the statistical assertions above are stable.
     */
    private static int hotSetSurvivors(int sampleSize) {
        int capacity = 50;
        int hotKeys = 10;
        var cache = new RedisSampledLRUCache<Integer, Integer>(capacity, sampleSize);
        for (int k = 0; k < hotKeys; k++) cache.put(k, k);

        int coldKey = 1_000;
        for (int round = 0; round < 500; round++) {
            for (int k = 0; k < hotKeys; k++) cache.get(k);     // refresh whatever hot keys remain
            for (int j = 0; j < 10; j++) cache.put(coldKey++, 0);
        }

        int survivors = 0;
        for (int k = 0; k < hotKeys; k++) if (cache.get(k) != null) survivors++;
        return survivors;
    }
}

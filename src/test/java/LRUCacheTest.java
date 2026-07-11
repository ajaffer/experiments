import org.experiments.cache.Cache;
import org.experiments.cache.LRUCache;
import org.experiments.cache.LRUCacheThreadSafe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class LRUCacheTest {

    // Every behavioral test runs against both implementations: the thread-safe
    // subclass must be observationally identical to the base cache.
    static Stream<Arguments> implementations() {
        return Stream.of(
                arguments("LRUCache", (IntFunction<Cache<Integer, Integer>>) LRUCache<Integer, Integer>::new),
                arguments("LRUCacheThreadSafe", (IntFunction<Cache<Integer, Integer>>) LRUCacheThreadSafe<Integer, Integer>::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void leetCodeTrace(String name, IntFunction<Cache<Integer, Integer>> newCache) {
        Cache<Integer, Integer> cache = newCache.apply(2);
        cache.put(1, 1);
        cache.put(2, 2);
        assertEquals(1, cache.get(1));      // 1 becomes MRU
        cache.put(3, 3);                    // evicts 2 (LRU)
        assertNull(cache.get(2));
        cache.put(4, 4);                    // evicts 1 (LRU)
        assertNull(cache.get(1));
        assertEquals(3, cache.get(3));
        assertEquals(4, cache.get(4));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void getMissReturnsNull(String name, IntFunction<Cache<Integer, Integer>> newCache) {
        Cache<Integer, Integer> cache = newCache.apply(2);
        assertNull(cache.get(42));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void getRefreshesRecency(String name, IntFunction<Cache<Integer, Integer>> newCache) {
        Cache<Integer, Integer> cache = newCache.apply(2);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.get(1);           // touch 1 so 2 is now the LRU
        cache.put(3, 3);        // evicts 2, not 1
        assertNull(cache.get(2));
        assertEquals(1, cache.get(1));
        assertEquals(3, cache.get(3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void updateExistingKeyReplacesValueAndRefreshesRecency(String name, IntFunction<Cache<Integer, Integer>> newCache) {
        Cache<Integer, Integer> cache = newCache.apply(2);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(1, 10);       // update value AND make 1 the MRU (no size growth)
        cache.put(3, 3);        // evicts 2, since 1 was just refreshed
        assertNull(cache.get(2));
        assertEquals(10, cache.get(1));
        assertEquals(3, cache.get(3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void capacityOfOneKeepsOnlyTheLatest(String name, IntFunction<Cache<Integer, Integer>> newCache) {
        Cache<Integer, Integer> cache = newCache.apply(1);
        cache.put(1, 1);
        cache.put(2, 2);        // evicts 1 immediately
        assertNull(cache.get(1));
        assertEquals(2, cache.get(2));
    }

    // Concurrency: capacity == key count, so nothing is evicted. If the locking
    // were wrong, concurrent list splices would corrupt the structure and lose or
    // NPE on entries; here every key must survive with its value.
    @Test
    void concurrentPutsAndGetsKeepEveryEntry() throws InterruptedException {
        int keys = 500;
        int threads = 16;
        Cache<Integer, Integer> cache = new LRUCacheThreadSafe<>(keys);

        List<Future<?>> results = runConcurrently(threads, () -> {
            for (int k = 0; k < keys; k++) {
                cache.put(k, k);
                cache.get(k);
            }
        });

        for (Future<?> r : results) assertDoesNotThrow(() -> r.get());
        for (int k = 0; k < keys; k++) assertEquals(k, cache.get(k), "lost key " + k);
    }

    // Concurrency under eviction: contents are nondeterministic, but the cache must
    // never throw or deadlock, and must remain usable afterward.
    @Test
    void concurrentEvictionDoesNotCorrupt() throws InterruptedException {
        int threads = 16;
        int opsPerThread = 5_000;
        Cache<Integer, Integer> cache = new LRUCacheThreadSafe<>(50);

        List<Future<?>> results = runConcurrently(threads, () -> {
            int x = 1;                                  // per-thread LCG; no shared RNG
            for (int i = 0; i < opsPerThread; i++) {
                x = x * 1103515245 + 12345;
                int key = Math.floorMod(x, 200);
                if ((i & 1) == 0) cache.put(key, key);
                else cache.get(key);
            }
        });

        for (Future<?> r : results) assertDoesNotThrow(() -> r.get());
        cache.put(999, 999);
        assertEquals(999, cache.get(999));
    }

    private static List<Future<?>> runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                start.await();                          // release all threads together to maximize contention
                task.run();
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "tasks did not finish (possible deadlock)");
        return results;
    }
}

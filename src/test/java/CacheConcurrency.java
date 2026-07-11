import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test support for exercising a cache from many threads at once. */
final class CacheConcurrency {

    private CacheConcurrency() {}

    /** Runs {@code task} on {@code threads} threads released together, and returns their futures. */
    static List<Future<?>> runConcurrently(int threads, Runnable task) throws InterruptedException {
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

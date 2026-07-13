package org.experiments.cache;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A miniature Caffeine: lock-free reads plus TinyLFU admission, showing how a production cache
 * keeps eviction off the read path — and how it stays scan-resistant, the failure mode plain
 * LRU can't handle.
 *
 * <p><b>Reads never block.</b> A {@code get} hits a {@link ConcurrentHashMap}; instead of
 * mutating a shared recency list (the write-on-read that forces a lock in a classic LRU), a hit
 * just records the key in a bounded, lossy read buffer. Writes append to a write buffer.
 *
 * <p><b>Maintenance runs under a {@code tryLock}, on a borrowed thread.</b> Whichever thread
 * wins {@code tryLock} drains both buffers and applies recency, admission, and eviction — there
 * is no dedicated eviction thread, so nothing to leak, and callers never block on it.
 *
 * <p><b>Admission is frequency-based (TinyLFU).</b> When full, a new key is admitted only if a
 * {@link FrequencySketch} says it's been seen at least as often as the eviction candidate; a
 * one-shot scan key (frequency ~1) therefore cannot evict a frequently-used key.
 *
 * <p>Simplifications vs. real Caffeine: single global buffers rather than striped ring buffers,
 * a {@code LinkedHashMap} for recency rather than W-TinyLFU's windowed segments, and no shared
 * executor. Notably, there is <i>no admission window</i>, so once full this cache is
 * conservative about newcomers — the "W" in W-TinyLFU is exactly the window that fixes that.
 * The point here is the shape, not the tuning.
 */
public class CaffeineLiteCache<U, V> implements Cache<U, V> {

    private static final int READ_BUFFER_MAX = 64;

    private final int capacity;
    private final ConcurrentHashMap<U, V> data = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<U> readBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger readBufferSize = new AtomicInteger();
    private final ConcurrentLinkedQueue<U> writeBuffer = new ConcurrentLinkedQueue<>();
    private final ReentrantLock maintenanceLock = new ReentrantLock();
    private final LinkedHashMap<U, Boolean> recency = new LinkedHashMap<>(16, 0.75f, true);  // access-ordered
    private final FrequencySketch<U> sketch;

    public CaffeineLiteCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.sketch = new FrequencySketch<>(capacity);
    }

    @Override
    public V get(U key) {
        V value = data.get(key);
        if (value == null) return null;

        recordRead(key);
        return value;
    }

    @Override
    public void put(U key, V value) {
        Objects.requireNonNull(value);
        data.put(key, value);
        writeBuffer.offer(key);
        tryDrain();
    }

    public int size() {
        return data.size();
    }

    /** Forces pending maintenance to run now (used by tests, and when a tight size is wanted). */
    public void cleanUp() {
        maintenanceLock.lock();
        try {
            drain();
        } finally {
            maintenanceLock.unlock();
        }
    }

    private void recordRead(U key) {
        if (readBufferSize.get() < READ_BUFFER_MAX) {
            readBuffer.offer(key);
            readBufferSize.incrementAndGet();
        }
        if (readBufferSize.get() >= READ_BUFFER_MAX) tryDrain();
    }

    private void tryDrain() {
        if (maintenanceLock.tryLock()) {
            try {
                drain();
            } finally {
                maintenanceLock.unlock();
            }
        }
    }

    // Runs only under maintenanceLock, so recency and the sketch are single-threaded in here.
    private void drain() {
        U key;
        while ((key = readBuffer.poll()) != null) {
            readBufferSize.decrementAndGet();
            sketch.increment(key);
            recency.get(key);                       // touch → most-recently-used (no-op if not admitted)
        }
        while ((key = writeBuffer.poll()) != null) {
            if (!data.containsKey(key)) continue;   // already evicted or rejected
            sketch.increment(key);
            if (recency.containsKey(key)) {
                recency.get(key);                   // existing key updated: just refresh its recency
            } else {
                admit(key);
            }
        }
    }

    private void admit(U candidate) {
        if (recency.size() < capacity) {
            recency.put(candidate, Boolean.TRUE);
            return;
        }
        U victim = recency.keySet().iterator().next();      // least-recently-used (recency non-empty: capacity >= 1)
        if (sketch.frequency(candidate) > sketch.frequency(victim)) {
            recency.remove(victim);
            data.remove(victim);
            recency.put(candidate, Boolean.TRUE);
        } else {
            data.remove(candidate);                 // TinyLFU: reject the low-frequency newcomer
        }
    }
}

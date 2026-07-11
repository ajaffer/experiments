package org.experiments.cache;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Approximate LRU cache using Redis's sampled-eviction strategy
 * ({@code maxmemory-policy allkeys-lru}). A {@code get} only stamps a last-access time —
 * it performs no structural mutation — so reads never contend on a shared recency list,
 * unlike a classic linked-list LRU where every read is a write. When the cache exceeds
 * capacity, eviction samples a handful of entries and drops the oldest, trading exact-LRU
 * precision for cheap, lock-free reads. Eviction quality improves with the sample size
 * (Redis found a sample of 10 approaches true LRU; 5 is its default).
 */
public class RedisSampledLRUCache<U, V> implements Cache<U, V> {

    private static final int DEFAULT_SAMPLE_SIZE = 5;

    private final int capacity;
    private final int sampleSize;
    private final ConcurrentHashMap<U, Entry<V>> map = new ConcurrentHashMap<>();

    private static final class Entry<V> {
        final V value;
        volatile long lastAccessNanos;

        Entry(V value, long now) {
            this.value = value;
            this.lastAccessNanos = now;
        }
    }

    public RedisSampledLRUCache(int capacity) {
        this(capacity, DEFAULT_SAMPLE_SIZE);
    }

    public RedisSampledLRUCache(int capacity, int sampleSize) {
        this.capacity = capacity;
        this.sampleSize = sampleSize;
    }

    @Override
    public V get(U key) {
        var entry = map.get(key);
        if (entry == null) return null;

        entry.lastAccessNanos = System.nanoTime();      // stamp only; a racing read may clobber, which is fine
        return entry.value;
    }

    @Override
    public void put(U key, V value) {
        Objects.requireNonNull(value);
        map.put(key, new Entry<>(value, System.nanoTime()));

        // size() is a concurrent estimate; bound the loop by eviction progress so a put
        // can never livelock, and accept that the cache is only eventually at capacity.
        while (map.size() > capacity && evictOldestSample()) {
            // keep evicting
        }
    }

    public int size() {
        return map.size();
    }

    private boolean evictOldestSample() {
        var sample = reservoirSample();

        U victim = null;
        long oldest = Long.MAX_VALUE;
        for (var e : sample) {
            long ts = e.getValue().lastAccessNanos;
            if (ts < oldest) {
                oldest = ts;
                victim = e.getKey();
            }
        }
        return victim != null && map.remove(victim) != null;
    }

    // A uniform random sample of up to sampleSize entries (reservoir sampling). The sample
    // MUST be random: taking, say, the first sampleSize entries of the map's iterator would
    // repeatedly hit the same hash buckets and never approximate recency. Redis draws its
    // random sample in O(1) via direct hash-bucket access; the JDK map exposes no such hook,
    // so drawing it costs a weakly-consistent O(size) pass — a real difference worth noting.
    private ArrayList<Map.Entry<U, Entry<V>>> reservoirSample() {
        var sample = new ArrayList<Map.Entry<U, Entry<V>>>(sampleSize);
        var random = ThreadLocalRandom.current();
        int seen = 0;
        for (var e : map.entrySet()) {
            if (sample.size() < sampleSize) sample.add(e);
            else {
                int j = random.nextInt(seen + 1);
                if (j < sampleSize) sample.set(j, e);
            }
            seen++;
        }
        return sample;
    }
}

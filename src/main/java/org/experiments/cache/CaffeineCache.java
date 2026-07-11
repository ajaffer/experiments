package org.experiments.cache;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Objects;

/**
 * Thin adapter exposing Caffeine behind the {@link Cache} interface, for side-by-side
 * comparison with the hand-rolled caches. Caffeine is the production answer the other two
 * are simplified stand-ins for: a lock-free read path (accesses are buffered and replayed
 * under a try-lock, never blocking readers) backed by W-TinyLFU admission, which weighs
 * frequency as well as recency and so resists the scan pattern that defeats plain LRU.
 */
public class CaffeineCache<U, V> implements Cache<U, V> {

    private final com.github.benmanes.caffeine.cache.Cache<U, V> delegate;

    public CaffeineCache(int capacity) {
        this.delegate = Caffeine.newBuilder().maximumSize(capacity).build();
    }

    @Override
    public V get(U key) {
        return delegate.getIfPresent(key);
    }

    @Override
    public void put(U key, V value) {
        Objects.requireNonNull(value);
        delegate.put(key, value);
    }

    public long size() {
        delegate.cleanUp();                 // force pending evictions so the estimate is tight
        return delegate.estimatedSize();
    }
}

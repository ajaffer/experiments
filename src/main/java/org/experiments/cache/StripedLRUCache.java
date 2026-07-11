package org.experiments.cache;

/**
 * Lock-striped LRU cache: a router over N independently-locked {@link LRUCacheThreadSafe}
 * shards. Keys that hash to different shards are served in parallel, so throughput scales
 * with the shard count under a well-spread key distribution — this is the lock-striping
 * lineage of {@code java.util.concurrent.ConcurrentHashMap}.
 *
 * <p>The trade-off: eviction is per-shard, so there is no single global LRU order. A
 * globally hot key in a cold shard can be evicted before colder keys in a busier shard,
 * and a skewed key distribution collapses back toward single-lock behavior on the hot
 * shard. Effective capacity is rounded up to {@code perShard * shardCount}, so the cache
 * may hold slightly more than the requested capacity.
 */
public class StripedLRUCache<U, V> implements Cache<U, V> {

    private static final int DEFAULT_SHARDS = 16;

    private final LRUCacheThreadSafe<U, V>[] shards;
    private final int mask;

    public StripedLRUCache(int capacity) {
        this(capacity, DEFAULT_SHARDS);
    }

    @SuppressWarnings("unchecked")
    public StripedLRUCache(int capacity, int shardCount) {
        int count = roundUpToPowerOfTwo(shardCount);
        this.mask = count - 1;
        this.shards = new LRUCacheThreadSafe[count];

        int perShard = Math.max(1, (capacity + count - 1) / count);     // ceiling division
        for (int i = 0; i < count; i++) this.shards[i] = new LRUCacheThreadSafe<>(perShard);
    }

    @Override
    public V get(U key) {
        return shardFor(key).get(key);
    }

    @Override
    public void put(U key, V value) {
        shardFor(key).put(key, value);      // the shard inherits null-value rejection from LRUCache
    }

    public int size() {
        int total = 0;
        for (var shard : shards) total += shard.size();
        return total;
    }

    private LRUCacheThreadSafe<U, V> shardFor(U key) {
        int h = key.hashCode();
        h ^= (h >>> 16);                    // fold high bits down so the low-bit mask sees their entropy
        return shards[h & mask];
    }

    private static int roundUpToPowerOfTwo(int n) {
        if (n <= 1) return 1;
        return Integer.highestOneBit(n - 1) << 1;
    }
}

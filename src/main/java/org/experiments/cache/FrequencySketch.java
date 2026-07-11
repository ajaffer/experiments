package org.experiments.cache;

/**
 * A tiny count-min sketch of access frequency for TinyLFU-style admission: bounded memory,
 * approximate counts, with periodic aging so it reflects recent popularity rather than
 * all-time totals. Each key maps to {@code COUNTERS} cells via different hashes; the estimate
 * is the minimum of those cells (the count-min trick that bounds over-counting from
 * collisions).
 *
 * <p>Not thread-safe — the owning cache touches it only under its maintenance lock. A faithful
 * W-TinyLFU (e.g. Caffeine) packs 4-bit counters and tunes the reset; this keeps one int per
 * cell for clarity.
 */
final class FrequencySketch<K> {

    private static final int COUNTERS = 4;          // cells (hashes) per key
    private static final int MAX_COUNT = 15;        // cap so aging stays meaningful
    private static final int[] SEEDS = {0x7f4a7c15, 0x9e3779b9, 0x3c6ef35f, 0x1b873593};

    private final int[] table;
    private final int mask;
    private final int sampleSize;                   // total increments before halving
    private int additions;

    FrequencySketch(int capacity) {
        int len = tableSizeFor(Math.max(capacity, COUNTERS));
        this.table = new int[len];
        this.mask = len - 1;
        this.sampleSize = 10 * Math.max(capacity, 1);
    }

    int frequency(K key) {
        int hash = spread(key.hashCode());
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < COUNTERS; i++) min = Math.min(min, table[indexOf(hash, i)]);
        return min;
    }

    void increment(K key) {
        int hash = spread(key.hashCode());
        boolean bumped = false;
        for (int i = 0; i < COUNTERS; i++) {
            int idx = indexOf(hash, i);
            if (table[idx] < MAX_COUNT) {
                table[idx]++;
                bumped = true;
            }
        }
        if (bumped && ++additions >= sampleSize) age();
    }

    // Halve every counter so recent popularity outweighs old — the "reset" step in TinyLFU.
    private void age() {
        for (int i = 0; i < table.length; i++) table[i] >>>= 1;
        additions >>>= 1;
    }

    private int indexOf(int hash, int i) {
        int h = hash ^ SEEDS[i];
        h *= 0x9e3779b1;
        h ^= h >>> 15;
        return h & mask;
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int tableSizeFor(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }
}

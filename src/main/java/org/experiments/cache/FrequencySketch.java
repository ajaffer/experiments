package org.experiments.cache;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

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
    // One seeded Murmur3 per row, so a key indexes COUNTERS independent cells. The seeds are
    // arbitrary — any distinct ints work; these are recognizable hashing constants.
    private static final int[] SEEDS = {0x7f4a7c15, 0x9e3779b9, 0x3c6ef35f, 0x1b873593};
    private static final HashFunction[] HASHERS = buildHashers();

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
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < COUNTERS; i++) min = Math.min(min, table[indexOf(key, i)]);
        return min;
    }

    void increment(K key) {
        boolean bumped = false;
        for (int i = 0; i < COUNTERS; i++) {
            int idx = indexOf(key, i);
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

    private int indexOf(K key, int row) {
        return HASHERS[row].hashInt(key.hashCode()).asInt() & mask;
    }

    private static HashFunction[] buildHashers() {
        var hashers = new HashFunction[COUNTERS];
        for (int i = 0; i < COUNTERS; i++) hashers[i] = Hashing.murmur3_32_fixed(SEEDS[i]);
        return hashers;
    }

    private static int tableSizeFor(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    // Teaching reference — intentionally unused. This is by hand what indexOf() now delegates to
    // Guava's seeded Murmur3 for: mix a per-row seed into the key's hash, scatter the bits, fold
    // the well-mixed high bits down, then mask into the power-of-two table. Production code should
    // lean on a vetted hash (as indexOf does) rather than hand-rolled constants; this stays only
    // to show the mechanism a count-min cell address is built from.
    @SuppressWarnings("unused")
    private int indexOfHandRolled(int keyHash, int row) {
        // A large odd multiplier (golden-ratio / xxHash PRIME32_1) — odd so the multiply is a
        // bijection mod 2^32 and loses no information.
        final int mixMultiplier = 0x9e3779b1;
        int h = keyHash ^ SEEDS[row];   // per-row seed → each of the COUNTERS cells indexes differently
        h ^= (h >>> 16);                // spread the raw hashCode's high bits before mixing
        h *= mixMultiplier;             // scatter the bits (avalanche)
        h ^= h >>> 15;                  // fold high bits down so the low-bit mask sees their entropy
        return h & mask;                // map into the power-of-two table
    }
}

package org.experiments.probabilistic;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;

/**
 * A Bloom filter: a compact, probabilistic set answering "definitely not present" or "possibly
 * present". It has <b>no false negatives</b> (a member always tests present), a tunable
 * false-positive rate, and <b>no deletions</b> — clearing a bit could erase a bit shared with
 * another member and introduce false negatives.
 *
 * <p>Its signature use is skipping work for absent keys: Cassandra and other LSM-tree stores keep
 * one filter per on-disk SSTable, so a lookup for a missing key is rejected in memory instead of
 * touching disk. Cousin of the count-min sketch — both are bit/counter arrays probed by several
 * hashes; a Bloom filter answers membership, a sketch answers frequency.
 *
 * <p>Sizing follows the standard optimal formulas from the expected element count {@code n} and
 * target false-positive rate {@code p}: {@code m = -n·ln(p) / (ln2)²} bits and {@code k = (m/n)·ln2}
 * hashes. The {@code k} indices come from two 64-bit halves of one Murmur3-128 hash via
 * Kirsch–Mitzenmacher double hashing ({@code gᵢ = h1 + i·h2}), which is as accurate as {@code k}
 * independent hashes but computes only one. A production filter (e.g. Guava's {@code BloomFilter})
 * hashes the whole object through a {@code Funnel}; this hashes {@code hashCode()} for simplicity.
 */
public final class BloomFilter<T> {

    private final BitSet bits;
    private final int numBits;
    private final int numHashes;
    private final HashFunction hasher = Hashing.murmur3_128();

    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) throw new IllegalArgumentException("expectedInsertions must be > 0");
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1)
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");
        this.numBits = optimalNumBits(expectedInsertions, falsePositiveRate);
        this.numHashes = optimalNumHashes(expectedInsertions, numBits);
        this.bits = new BitSet(numBits);
    }

    public void add(T item) {
        long[] h = doubleHash(item);
        long combined = h[0];
        for (int i = 0; i < numHashes; i++) {
            bits.set(indexFor(combined));
            combined += h[1];               // gᵢ = h1 + i·h2
        }
    }

    /** Returns false if {@code item} is definitely absent, true if it is possibly present. */
    public boolean mightContain(T item) {
        long[] h = doubleHash(item);
        long combined = h[0];
        for (int i = 0; i < numHashes; i++) {
            if (!bits.get(indexFor(combined))) return false;    // any clear bit → definitely absent
            combined += h[1];
        }
        return true;                        // all bits set → possibly present (or a false positive)
    }

    public int bitSize() {
        return numBits;
    }

    public int hashCount() {
        return numHashes;
    }

    // Splits one Murmur3-128 into two 64-bit halves — the h1, h2 that seed the double-hashing
    // loop. asBytes() is little-endian, so the buffer reads the halves back in that order.
    private long[] doubleHash(T item) {
        var buf = ByteBuffer.wrap(hasher.hashInt(item.hashCode()).asBytes()).order(ByteOrder.LITTLE_ENDIAN);
        return new long[] {buf.getLong(), buf.getLong()};
    }

    private int indexFor(long combined) {
        return (int) Long.remainderUnsigned(combined, numBits);     // unsigned so negatives map in range
    }

    private static int optimalNumBits(int n, double p) {
        return (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    private static int optimalNumHashes(int n, int m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }
}

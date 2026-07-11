package org.experiments.hashing;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.ToDoubleFunction;

/**
 * Why a consistent-hash ring needs an avalanching hash (Murmur3), not {@code Object.hashCode()}.
 * It places one node's virtual nodes on the ring under each hash. Their names differ only in a
 * trailing character ("nodeA#0".."nodeA#15"), so {@code String.hashCode()} — a polynomial where
 * the last char contributes a weight of 1 — maps them to near-consecutive values that clump into
 * a point, while Murmur3 scatters them around the ring. Only a scattered placement delivers the
 * even load that vnodes are supposed to provide.
 */
public class HashSpread {

    private static final int VNODES = 16;
    private static final int WIDTH = 72;        // characters across the ring strip

    public static void main(String[] args) {
        System.out.println("Ring positions of nodeA#0 .. nodeA#" + (VNODES - 1)
                + " — one node's " + VNODES + " vnodes (0.0 = ring start, 1.0 = ring end):\n");
        show("String.hashCode()", HashSpread::hashCodePosition);
        show("Murmur3-128", HashSpread::murmurPosition);
    }

    private static void show(String label, ToDoubleFunction<String> position) {
        double[] positions = new double[VNODES];
        for (int i = 0; i < VNODES; i++) positions[i] = position.applyAsDouble("nodeA#" + i);

        System.out.println(String.format("%-18s", label) + strip(positions));
        System.out.printf("%18slargest empty arc = %5.1f%% of the ring   (even spread ≈ %.1f%%)%n%n",
                "", 100 * largestGap(positions), 100.0 / VNODES);
    }

    // A ring laid flat: each cell shows how many vnodes landed there ('·' none, '|' one, a digit
    // for a few, '*' for a pile-up). Clumping shows as one crowded cell; good spread fills the row.
    private static String strip(double[] positions) {
        int[] hits = new int[WIDTH];
        for (double p : positions) hits[Math.min(WIDTH - 1, (int) (p * WIDTH))]++;
        var sb = new StringBuilder("[");
        for (int c : hits) sb.append(c == 0 ? '·' : c == 1 ? '|' : c <= 9 ? (char) ('0' + c) : '*');
        return sb.append(']').toString();
    }

    // Largest arc of ring with no vnode on it. ~100% means everything clumped into one spot;
    // near the even-spread ideal (1/VNODES) means the vnodes are well distributed.
    private static double largestGap(double[] positions) {
        double[] sorted = positions.clone();
        Arrays.sort(sorted);
        double max = sorted[0] + 1.0 - sorted[sorted.length - 1];       // the wrap-around arc
        for (int i = 1; i < sorted.length; i++) max = Math.max(max, sorted[i] - sorted[i - 1]);
        return max;
    }

    private static double hashCodePosition(String key) {
        return (key.hashCode() & 0xFFFFFFFFL) / 4_294_967_296.0;        // unsigned hashCode / 2^32
    }

    private static double murmurPosition(String key) {
        long h = Hashing.murmur3_128().hashString(key, StandardCharsets.UTF_8).asLong();
        return (h >>> 11) * 0x1.0p-53;                                   // top 53 bits mapped into [0,1)
    }
}

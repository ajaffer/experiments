package org.experiments;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class ConsistentHashing {

    // One shared, immutable hash function. Murmur3 128-bit: fast, great spread,
    // non-cryptographic — the right tool for partitioning/rings.
    private static final HashFunction HASH = Hashing.murmur3_128();

    // The starting cluster: 3 nodes, before a 4th joins.
    private static final List<String> NODES = List.of("nodeA", "nodeB", "nodeC");

    // ---- Part 1: fixed-partition routing ----
    static int partitionFor(String key, int numPartitions) {
        return Math.floorMod(hash32(key), numPartitions);
    }

    // ---- Part 2: consistent hashing ring ----
    static final class ConsistentHashRing {
        private final TreeMap<Long, String> ring = new TreeMap<>();
        private final int vnodesPerNode;
        ConsistentHashRing(int vnodesPerNode) { this.vnodesPerNode = vnodesPerNode; }
        void addNode(String node) {
            for (int i = 0; i < vnodesPerNode; i++) ring.put(hash64(node + "#" + i), node);
        }
        void removeNode(String node) {
            for (int i = 0; i < vnodesPerNode; i++) ring.remove(hash64(node + "#" + i));
        }
        String getNode(String key) {
            if (ring.isEmpty()) throw new IllegalStateException("ring has no nodes");
            Map.Entry<Long, String> e = ring.ceilingEntry(hash64(key));
            return (e != null ? e : ring.firstEntry()).getValue();
        }
    }

    // ---- hashing helpers ----
    static long hash64(String s) {
        return HASH.hashString(s, StandardCharsets.UTF_8).asLong();
    }
    static int hash32(String s) {
        return HASH.hashString(s, StandardCharsets.UTF_8).asInt();
    }

    // ---- demo ----
    public static void main(String[] args) {
        System.out.println("== Part 1: fixed-partition routing (P=4) ==");
        for (String k : List.of("user_a", "user_b", "user_c", "user_d", "user_e"))
            System.out.printf("  %-8s -> partition %d%n", k, partitionFor(k, 4));


        int K = 10_000;
        System.out.println("\n== Part 2: adding one worker (3 -> 4), " + K);
        Map<Integer, Result> byVnodes = new LinkedHashMap<>();
        for (int vnodesPerNode = 1; vnodesPerNode <= 256; vnodesPerNode++)
            byVnodes.put(vnodesPerNode, run(vnodesPerNode, K, 30));

        System.out.println();
        printSummary(byVnodes, 1, 8, 16, 32, 64, 128, 256);
    }

    private record Result(double avgMoved, double minMoved, double maxMoved, double imbalance, double cv) {}

    private static Result run(int vnodesPerNode, int K, int trials) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < K; i++) keys.add("key-" + i);

        double sum = 0; double min = 100, max = 0; double imbSum = 0; double cvSum = 0;
        for (int t = 0; t < trials; t++) {
            String salt = "-run" + t;                       // different ring each trial

            ConsistentHashRing ring = new ConsistentHashRing(vnodesPerNode);
            for (String n : NODES)
                ring.addNode(n + salt);                      // salt shifts node positions
            Map<String, String> before = new HashMap<>();
            for (String k : keys) before.put(k, ring.getNode(k));
            Collection<Integer> load = loadPerNode(before);  // load balance of the 3-node ring
            imbSum += imbalance(load);
            cvSum += loadCv(load);
            ring.addNode("nodeD" + salt);

            int moved = 0;
            for (String k : keys) if (!ring.getNode(k).equals(before.get(k))) moved++;
            double pct = 100.0 * moved / K;
            sum += pct; min = Math.min(min, pct); max = Math.max(max, pct);
        }
        System.out.printf("vnodes=%3d  avg=%4.1f%%  (min %4.1f, max %4.1f)  spread=%4.1f  imbalance=%4.2fx  cv=%4.2f%n",
                vnodesPerNode, sum/trials, min, max, max - min, imbSum/trials, cvSum/trials);
        return new Result(sum/trials, min, max, imbSum/trials, cvSum/trials);
    }

    // Renders a box-drawing summary of imbalance and cv at the given vnode levels.
    private static void printSummary(Map<Integer, Result> byVnodes, int... levels) {
        String[] header = new String[levels.length + 1];
        String[] imbalance = new String[levels.length + 1];
        String[] cv = new String[levels.length + 1];
        String[] theory = new String[levels.length + 1];
        header[0] = "";
        imbalance[0] = "imbalance (busiest node)";
        cv[0] = "cv (whole distribution)";
        theory[0] = "cv theory  √((N-1)/(NV+1))";
        for (int i = 0; i < levels.length; i++) {
            Result r = byVnodes.get(levels[i]);
            header[i + 1] = levels[i] + (levels[i] == 1 ? " vnode" : " vnodes");
            imbalance[i + 1] = String.format("%.2fx", r.imbalance());
            cv[i + 1] = String.format("%.2f", r.cv());
            theory[i + 1] = String.format("%.2f", theoryCv(NODES.size(), levels[i]));
        }
        printBoxTable(List.of(header, imbalance, cv, theory));
    }

    // Closed-form CV of per-node load for N nodes with V vnodes each. The N*V vnode
    // points cut the ring into N*V uniform-random arcs; a node owns V of them, so its
    // load is a sum of V arc lengths whose CV works out to sqrt((N-1)/(N*V+1)).
    private static double theoryCv(int nodes, int vnodes) {
        return Math.sqrt((nodes - 1.0) / (nodes * vnodes + 1));
    }

    // Prints rows as a bordered table; row 0 is the header. Column widths auto-fit.
    private static void printBoxTable(List<String[]> rows) {
        // Pass 1: width[c] = widest cell in column c, so every row and border can be
        // drawn to a shared column size and the vertical dividers line up.
        int[] width = new int[rows.get(0).length];
        for (String[] row : rows)
            for (int c = 0; c < row.length; c++) width[c] = Math.max(width[c], row[c].length());
        // Pass 2: draw borders and rows against those widths.
        printBorder(width, '┌', '┬', '┐');
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) printBorder(width, '├', '┼', '┤');
            printRow(rows.get(r), width);
        }
        printBorder(width, '└', '┴', '┘');
    }

    // width[c] + 2 spans the one padding space on each side of a cell, matching printRow
    // so the ┬/┼/┴ junctions sit exactly above the │ dividers.
    private static void printBorder(int[] width, char left, char mid, char right) {
        StringBuilder sb = new StringBuilder().append(left);
        for (int c = 0; c < width.length; c++)
            sb.append("─".repeat(width[c] + 2)).append(c == width.length - 1 ? right : mid);
        System.out.println(sb);
    }

    // Each cell is " " + value left-justified to width[c] + " │"; the %- padding makes
    // every closing divider land in the same column regardless of the value's length.
    private static void printRow(String[] cells, int[] width) {
        StringBuilder sb = new StringBuilder().append('│');
        for (int c = 0; c < cells.length; c++)
            sb.append(' ').append(String.format("%-" + width[c] + "s", cells[c])).append(" │");
        System.out.println(sb);
    }

    // Number of keys owned by each node in a key->node assignment.
    private static Collection<Integer> loadPerNode(Map<String, String> ownerByKey) {
        Map<String, Integer> keysPerNode = new HashMap<>();
        for (String node : ownerByKey.values()) keysPerNode.merge(node, 1, Integer::sum);
        return keysPerNode.values();
    }

    // Load skew: the busiest node's key count divided by the fair share (average keys
    // per node). 1.00x = perfectly even; 2.00x = the busiest node holds twice its share.
    private static double imbalance(Collection<Integer> loadPerNode) {
        int busiestLoad = loadPerNode.stream().max(Integer::compare).orElse(0);
        return busiestLoad / average(loadPerNode);
    }

    // Coefficient of variation of the per-node load (stddev / average): a scale-free
    // measure of overall balance across all nodes. 0.00 = perfectly even; smaller is better.
    private static double loadCv(Collection<Integer> loadPerNode) {
        double avg = average(loadPerNode);
        double variance = loadPerNode.stream()
                .mapToDouble(n -> (n - avg) * (n - avg)).sum() / loadPerNode.size();
        return Math.sqrt(variance) / avg;
    }

    private static double average(Collection<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).sum() / (double) values.size();
    }
}

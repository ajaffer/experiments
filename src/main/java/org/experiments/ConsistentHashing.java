package org.experiments;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class ConsistentHashing {

    // One shared, immutable hash function. Murmur3 128-bit: fast, great spread,
    // non-cryptographic — the right tool for partitioning/rings.
    private static final HashFunction HASH = Hashing.murmur3_128();

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
        System.out.println("\n== Part 2: adding one worker (3 -> 4), " + K + " keys ==");
        for (int vnodesPerNode = 1; vnodesPerNode <=16; vnodesPerNode++) {
            run(vnodesPerNode, K, 30);
        }
    }

    private static void run(int vnodesPerNode, int K, int trials) {
        double sum = 0; double min = 100, max = 0; double imbSum = 0;
        for (int t = 0; t < trials; t++) {
            String salt = "-run" + t;                       // different ring each trial
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < K; i++) keys.add("key-" + i);

            ConsistentHashRing ring = new ConsistentHashRing(vnodesPerNode);
            for (String n : List.of("nodeA", "nodeB", "nodeC"))
                ring.addNode(n + salt);                      // salt shifts node positions
            Map<String, String> before = new HashMap<>();
            for (String k : keys) before.put(k, ring.getNode(k));
            imbSum += imbalance(before, 3);                  // load balance of the 3-node ring
            ring.addNode("nodeD" + salt);

            int moved = 0;
            for (String k : keys) if (!ring.getNode(k).equals(before.get(k))) moved++;
            double pct = 100.0 * moved / K;
            sum += pct; min = Math.min(min, pct); max = Math.max(max, pct);
        }
        System.out.printf("vnodes=%3d  avg=%4.1f%%  (min %4.1f, max %4.1f)  spread=%4.1f  imbalance=%4.2fx%n",
                vnodesPerNode, sum/trials, min, max, max - min, imbSum/trials);
    }

    // Load skew of a key->node assignment: busiest node's share of keys relative to
    // the fair share (1.00x = perfectly even; 2.00x = busiest node holds twice its share).
    private static double imbalance(Map<String, String> ownerByKey, int numNodes) {
        Map<String, Integer> load = new HashMap<>();
        for (String node : ownerByKey.values()) load.merge(node, 1, Integer::sum);
        int max = load.values().stream().max(Integer::compare).orElse(0);
        double mean = (double) ownerByKey.size() / numNodes;
        return max / mean;
    }
}

import org.experiments.probabilistic.BloomFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BloomFilterTest {

    // The defining guarantee: a member always tests present. Never a false negative.
    @Test
    void everyAddedItemIsReportedPresent() {
        var filter = new BloomFilter<Integer>(10_000, 0.01);
        for (int i = 0; i < 10_000; i++) filter.add(i);
        for (int i = 0; i < 10_000; i++) assertTrue(filter.mightContain(i), "false negative for " + i);
    }

    @Test
    void emptyFilterContainsNothing() {
        var filter = new BloomFilter<String>(100, 0.01);
        assertFalse(filter.mightContain("anything"));
    }

    // False positives are allowed but must stay near the configured rate. Absent keys are a
    // disjoint integer range, so any "present" answer is a false positive.
    @Test
    void falsePositiveRateStaysNearTarget() {
        int n = 10_000;
        double target = 0.01;
        var filter = new BloomFilter<Integer>(n, target);
        for (int i = 0; i < n; i++) filter.add(i);

        int falsePositives = 0;
        for (int i = n; i < 2 * n; i++) if (filter.mightContain(i)) falsePositives++;
        double observed = (double) falsePositives / n;

        assertTrue(observed <= target * 3,
                "false-positive rate " + observed + " far above target " + target);
    }

    @Test
    void sizingFollowsTheOptimalFormulas() {
        var filter = new BloomFilter<Integer>(10_000, 0.01);
        assertEquals(7, filter.hashCount());        // round((m/n)·ln2) for p=0.01
        assertTrue(filter.bitSize() > 90_000 && filter.bitSize() < 100_000);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<Integer>(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<Integer>(100, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<Integer>(100, 1.0));
    }
}

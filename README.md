# experiments

A scratch repo for small, self-contained code snippets I'm fiddling with. Each
experiment stands alone — no shared framework, just runnable examples.

## Stack

Java 23 · Gradle 9.3 (wrapper) · JUnit 5 · Guava.

## Build & run

```bash
./gradlew build          # compile + test
./gradlew test           # tests only
./gradlew run            # run the default experiment
```

`run` launches `org.experiments.ConsistentHashing` by default. Point it at any
other experiment's `main` with:

```bash
./gradlew run -PmainClass=org.experiments.OtherExperiment
```

## Experiments

### `org.experiments.ConsistentHashing`

Explores how a consistent-hashing ring behaves as you vary the number of virtual
nodes (vnodes) per physical node. Two parts:

1. **fixed-partition routing** — `hash(key) mod N`, the naive approach whose
   ownership is upended whenever `N` changes.
2. **consistent-hash ring** — a `TreeMap`-backed ring (Murmur3-128 hashing) with
   configurable vnodes per node.

Part 2 sweeps vnodes from 1 to 16 and, over 30 randomized rings, reports two
things when a 4th node joins a 3-node cluster (10k keys):

- **movement** — the fraction of keys that relocate. The average sits near the
  theoretical 25% regardless of vnodes (it's set by node count), but the
  **spread** between best- and worst-case rings shrinks sharply as vnodes rise.
- **imbalance** — the busiest node's key share relative to a fair share
  (`max load ÷ mean load`). This is what vnodes really fix: it falls from ~1.76x
  at 1 vnode to ~1.20x at 16.

```
vnodes=  1  avg=27.6%  (min  0.7, max 82.2)  spread=81.5  imbalance=1.76x
vnodes=  8  avg=24.5%  (min 11.1, max 39.1)  spread=28.0  imbalance=1.30x
vnodes= 16  avg=24.9%  (min 12.9, max 38.7)  spread=25.8  imbalance=1.20x
```

**Takeaway:** more vnodes → tighter load balance and less variance in how much
data reshuffles on a topology change, with diminishing returns past ~8–10.

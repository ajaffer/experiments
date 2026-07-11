# Concurrent caching — interview notes

Study companion for the cache experiments in `org.experiments.cache`, aimed at an
infrastructure/caching role (Redis/Cassandra-scale). The code is the *setup*; the
tradeoff discussion below is the *answer*. The single most important move in the room:
after you write a correct cache, **volunteer why the naive concurrent version doesn't
scale and what you'd build instead** — don't wait to be asked.

## The progression (this is the whole story)

| Stage | Artifact | Idea | Wins | Limits |
|---|---|---|---|---|
| 1. Naive lock | `LRUCacheThreadSafe` | one `ReentrantLock` around get/put | trivially correct | **serializes everything**; because `get` mutates recency, reads can't even run in parallel |
| 2. Lock striping | `StripedLRUCache` | N shards, each own lock, route by hashed key | ~N× less contention under spread load | no global LRU order; hot-shard skew collapses back to stage 1 |
| 3. Approximate LRU | `RedisSampledLRUCache` | reads only stamp a timestamp (no structural mutation → **lock-free reads**); evict by sampling | reads never block or contend | eviction is approximate; sampling cost (below) |
| 4. Production | `CaffeineCache` (adapter) | lock-free reads via buffered replay + **W-TinyLFU** admission | best hit rate, scan-resistant | it's a library — know *why* it wins |

The arc to narrate: **strict LRU is the wrong data structure for a concurrent cache.**
Exact recency ordering needs a globally mutable list, which forces serialization. Every
real hyperscale cache relaxes that — either by sharding the lock domain, or by giving up
exact ordering for cheap reads.

## The key insight per stage

**Stage 1 — why `get` being a mutation is the whole problem.** In a classic linked-list
LRU, a *read* moves the node to the front — it writes shared state. So you can't use a
read/write lock's read side (two concurrent hits both rewrite the list → corruption).
That's why `LRUCacheThreadSafe` uses one exclusive lock for both. Correct, but it means
**reads are as expensive as writes**, and reads dominate cache traffic. This is the
bottleneck everything else attacks.

**Stage 2 — lock striping.** `ConcurrentHashMap`'s trick: partition into N shards, each
with its own lock; a key's shard is `spread(hash) & (N-1)`. Operations on different shards
proceed fully in parallel. Two things to say:
- **Spread the hash.** Java's `Integer.hashCode()` is the identity, and a low-bit mask
  ignores the high bits, so poorly-distributed keys pile onto one shard. Fold the top bits
  down (`h ^= h >>> 16` — literally `HashMap.spread`) before masking.
- **It's already approximate.** Eviction is per-shard, so there's no global LRU. A hot key
  in a cold shard can be evicted while colder keys survive elsewhere. Striping trades exact
  global capacity/ordering for concurrency — capacity rounds up to `perShard × shards`.

**Stage 3 — Redis sampled LRU (`maxmemory-policy allkeys-lru`).** Stop mutating on reads:
store a `volatile long lastAccessNanos` per entry; `get` just stamps it (a racing read may
clobber it — benign, recency is approximate anyway). No list, no lock on reads. Eviction
picks a **random sample** of K entries and drops the oldest; quality rises with K (Redis
found K=10 ≈ true LRU; K=5 is the default). Two subtleties:
- **The sample must be random.** Taking "the first K of the map's iterator" repeatedly hits
  the same hash buckets and never approximates recency — it'll happily evict your hot set.
  (This experiment hit exactly that bug; the fix is reservoir sampling.)
- **Sampling cost — the honest caveat, and a great talking point.** Redis draws its random
  sample in *O(1)* because its own hash table gives direct random bucket access. Java's
  `ConcurrentHashMap` exposes no such hook, so drawing a uniform sample costs an *O(size)*
  pass. At that cost, exact-oldest would be just as cheap — so *sampling's real payoff needs
  O(1) random access*. In production Java you'd maintain an index array for O(1) random
  sampling, or just use Caffeine. Being able to say this is the point.

**Stage 4 — Caffeine / W-TinyLFU (the production answer).** Reads record accesses into
per-thread ring buffers (no lock); a maintenance pass drains them under a `tryLock` and
applies recency to the real eviction structure — so readers never block. Admission uses
**W-TinyLFU**: a count-min sketch tracks *frequency*, and a new entry is admitted only if
it's likely more valuable than the eviction candidate. This makes it **scan-resistant** —
the failure mode plain LRU can't handle.

## Failure modes to name unprompted

- **Scan resistance.** One big sequential scan (a batch job, a range query) touches a huge
  set of one-shot keys and, under LRU, **evicts your entire hot working set**. LFU / LRU-K /
  ARC / TinyLFU exist because of this. Knowing *when LRU is wrong* is the senior signal.
- **Cache stampede / thundering herd.** A hot key expires; now N threads all miss and all
  recompute it against the backing store simultaneously. Fix: single-flight loading
  (`computeIfAbsent`-style coordination) so exactly one thread loads while the rest wait;
  optionally probabilistic early expiry to avoid synchronized expiry.
- **Hot-shard / hot-key skew.** Striping assumes uniform key distribution; a single viral
  key serializes its shard. Mitigations: better hashing, per-key request coalescing, or a
  tiny front cache for the top-K keys.

## CAP applied to caches (they'll connect it to the JD)

A cache is a replicated, weakly-consistent view of a source of truth. The tension is
**consistency vs. availability/latency on invalidation**:
- **TTL** — cheap, but serves stale data for up to the TTL (AP-leaning).
- **Write-through / write-behind** — fresher, but couples cache writes to the store.
- **Explicit invalidation** — a distributed-systems problem in itself (missed/reordered
  invalidations → stale entries); this is why cache invalidation is "one of the two hard
  things."
- **Distributed placement** — which node caches a key is **consistent hashing** (see the
  `org.experiments.hashing` experiment); it's how Redis Cluster and Cassandra shard, and
  how you add/remove cache nodes without reshuffling everything.

## Maps directly to the job description

- *"Deep understanding of multi-threading and design of highly concurrent applications"* →
  the stage 1→3 progression: lock → striping → lock-free reads, and *why `get`-as-mutation
  is the crux*.
- *"Redis, Cassandra"* → `RedisSampledLRUCache` **is** `allkeys-lru`; consistent hashing is
  Cassandra/Redis-Cluster placement.
- *"CAP theorem, distributed systems"* → the caching-CAP section above.
- *"TDD, testing methodologies"* → note how you test an *approximate* structure: not by
  exact trace but by **invariants** (size bounded, no corruption under a 16-thread storm)
  and **statistics** (a hot set survives; larger sample size retains it better) — see
  `ApproximateCacheTest`. And say the concurrency tests are *probabilistic* (they catch
  broken locking but can't prove correctness — jcstress is the tool that can).

## One-liners to have ready

- "A single lock is correct but it's a scalability ceiling — and since reads mutate recency,
  it doesn't even let reads run in parallel."
- "Strict LRU needs a global mutable list, which is fundamentally serial. Concurrent caches
  relax exact ordering to buy concurrency."
- "Redis samples because its hash table gives O(1) random access; you can't do that on a
  JDK `ConcurrentHashMap` for free."
- "Plain LRU isn't scan-resistant — that's the whole reason TinyLFU exists."
- "The hardest part of caching isn't the data structure, it's invalidation and stampedes."

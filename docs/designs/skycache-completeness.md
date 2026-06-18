# Skycache: Remaining Work for a Complete, Fast, and Correct Implementation

> Status: design / gap-analysis note. Audience: engineers continuing the
> remote analysis caching ("Skycache") effort.
> Scope: the open-source Bazel tree under
> `src/main/java/com/google/devtools/build/lib/skyframe/serialization/`.

## 1. Executive summary

Skycache serializes Skyframe analysis-phase SkyValues (and a subset of
execution-phase values) and stores them remotely, so a later invocation can
**download and deserialize** them instead of re-running analysis. The
**client-side engine is essentially complete and tested**; what is missing is
the **service-integration half** — the concrete remote store, the cache/lookup
client, and the metadata writer — which today sit behind a stable interface
boundary (`@SkybridgeInterface`, the "SC/LC" split) and have **no non-test
implementations in this repository**.

Concretely, out of the box:

- `UPLOAD` serializes correctly but writes to an **in-memory, non-persistent**
  store (`InMemoryFingerprintValueStore`), so nothing survives the invocation.
- `DOWNLOAD` / `BIDI` find a `null` cache client and **silently fall back to
  local analysis** (`RemoteAnalysisCacheFactory.create`,
  `RemoteAnalysisCacheFactory.java:275-299`).
- Metadata writes report *"MetadataAnalysisCacheWriterService is unavailable"*
  (`BuildTool.tryWriteSkycacheMetadata`, `BuildTool.java:1112-1116`).

This document enumerates the work to make Skycache **functional end-to-end**,
**correct** (no stale/incorrect cache hits), and **fast** (net build-time win
at scale), with file references, acceptance criteria, and a phased roadmap.

## 2. Current architecture (what exists today)

### 2.1 Serialization / deserialization engine — PRESENT

| Concern | Key classes |
| --- | --- |
| Write path | `FrontierSerializer.serializeAndUploadFrontier`, `SelectedEntrySerializer`, `FileDependencySerializer`, `FileOpNodeMemoizingLookup`, `SkycacheUploadClient` (async/during-build upload) |
| Read path | `SkyValueRetriever.tryRetrieve` (Skyframe-restart-aware state machine), `SkyValueRetrieverUtils`, `SharedValueDeserializationContext`, `RemoteConfiguredTargetValue` / `DeserializedSkyValue` |
| Cache key / versioning | `FrontierNodeVersion` (config checksum + install MD5 + Starlark semantics fingerprint + evaluating version + snapshot) |
| Invalidation | `AnalysisCacheInvalidator`, `RemoteAnalysisCachingServerState` |
| Orchestration / config | `RemoteAnalysisCacheFactory`, `RemoteAnalysisCacheManager`, `RemoteAnalysisCacheDeps`, `RemoteAnalysisCachingOptions`, `RemoteAnalysisCacheMode` |
| Observability | `RemoteAnalysisCachingEventListener`, `SkyValueUploadedEvent`, BEP events, `ProfilerTask` entries, stats in `MetricsCollector` |
| Scoping | active-directories matcher (PROJECT.scl / `--experimental_active_directories`) |
| Tests | `BazelSkycacheIntegrationTest`, `SkycacheIntegrationTestBase`, `SkyValueRetrieverTest`, `AnalysisCacheInvalidatorTest`, `TreeArtifactValueCodecTest` |

The retrieval path is a proper restart-driven state machine
(`SkyValueRetriever.java:180+`: `InitialQuery` →
`WaitingForCacheServiceResponse` → `WaitingForFutureLookupContinuation` →
`WaitingForLookupContinuation` → `RetrievedValue` / `NoCachedData`), correctly
returning `Restart.RESTART` when blocked on async I/O or missing Skyframe deps.

### 2.2 The SC/LC boundary (Skybridge) — INTERFACES ONLY

`@SkybridgeInterface` marks the *"SC/LC stable interface"*
(`SkybridgeInterface.java`) — *"the main boundary between the SC and the LC for
Skycache"* (`RemoteAnalysisCachingServicesSupplier.java`). The following are
**interfaces with no shipped production implementation**:

- `RemoteAnalysisCacheClient` — `lookup(byte[])`, `lookupTopLevelTargets(...)`,
  `getStats()`, `bailOutDueToMissingFingerprint()`. Only implementer in the tree
  is a test mock.
- `RemoteAnalysisMetadataWriter` — `addTopLevelTargets(...)`. No implementer.
- `RemoteAnalysisCachingServicesSupplier` — the injection point. The only
  shipped implementation, `SerializationModule.InMemoryRemoteAnalysisCachingServicesSupplier`,
  returns an in-memory store and `null` for both the cache client and the
  metadata writer.
- `FingerprintValueStore` — the content-addressable blob store. Only
  `InMemoryFingerprintValueStore` (prod-registered) and test doubles exist;
  there is an explicit `TODO: b/358347099 - use a persistent store`
  (`SerializationModule.java:96`).

## 3. Gap analysis

| Capability | State | Blocking? |
| --- | --- | --- |
| Serialize analysis frontier | Done | — |
| Deserialize on download | Done (engine) | — |
| Persistent/remote blob store (`FingerprintValueStore`) | **Missing** (in-memory only) | Yes — nothing persists |
| `RemoteAnalysisCacheClient` (lookup / invalidation queries) | **Missing** | Yes — `DOWNLOAD`/`BIDI` fall back to local |
| `RemoteAnalysisMetadataWriter` | **Missing** | Yes — no top-level-target metadata |
| Write proxy / read proxy clients | **Missing** | Yes for proxy deployments |
| Cross-invocation correctness (invalidation) | Partial / unproven at scale | Correctness risk |
| `--experimental_skycache_minimize_memory` | Marked "DO NOT USE… does not work with every target" | Quality |
| Upload selectivity (only freshly-computed nodes) | `TODO: b/371508153` | Speed/correctness |
| Docs / flag stabilization | All flags `UNDOCUMENTED`, experimental | UX |

## 4. Work items for a COMPLETE implementation (functional)

### W1. Persistent, remote `FingerprintValueStore`
**Why:** This is the content-addressable store for all serialized blobs.
Without persistence, no caching benefit survives a single invocation.
**Work:**
- Implement a production `FingerprintValueStore` backed by a real CAS
  (e.g. the existing remote-cache gRPC backend / `ByteStream`, or a dedicated
  service). Honor `put(KeyBytesProvider, byte[])` returning an async
  `WriteStatus`, and `get(KeyBytesProvider)` returning `ListenableFuture<byte[]>`.
- Wire batching, concurrency, and deadlines from
  `RemoteAnalysisCachingOptions` (`getMaxBatchSize`, `getConcurrency`,
  `getMaxWriteConcurrency`, `getTargetWriteConcurrency`, `getDeadline`).
- Surface stats into `FingerprintValueStore.Stats` (already consumed by
  `MetricsCollector.java:432` and `RemoteAnalysisCachingEventListener`).
**Acceptance:** an `UPLOAD` build by machine A produces blobs that a `DOWNLOAD`
build by machine B can fetch and deserialize; survives server restart.

### W2. Production `RemoteAnalysisCacheClient`
**Why:** `DOWNLOAD`/`BIDI` are dead without it (null-client fallback path).
**Work:**
- Implement `lookup(byte[] key)` returning `ListenableFuture<LookupResult>`,
  including the `missReason` plumbing already consumed by
  `SkyValueRetriever` (`SkyValueRetriever.java:213-220`).
- Implement `lookupTopLevelTargets(...)` against the metadata table and
  `bailOutDueToMissingFingerprint()` to set
  `MATCH_STATUS_MISSING_FINGERPRINT`.
- Populate `Stats` (bytes/requests/batches/latency buckets) for BEP/metrics.
- Respect version skew gracefully (unknown enum → `MISS_REASON_UNSPECIFIED`,
  already handled on the read side).
**Acceptance:** `DOWNLOAD` mode produces real cache hits and reduces analysis
work; `BazelSkycacheIntegrationTest`-style coverage passes against a real
(or fake-but-persistent) client, not just the in-memory mock.

### W3. Production `RemoteAnalysisMetadataWriter`
**Why:** Top-level-target metadata drives the "is a hit even possible" decision
(b/425247333) and `lookupTopLevelTargets`.
**Work:** Implement `addTopLevelTargets(...)`; ensure failures stay non-fatal
(`BuildTool.tryWriteSkycacheMetadata` already treats write failures as
warnings). Decide how metadata-query enablement
(`--experimental_analysis_cache_enable_metadata_queries`, default true)
interacts when the writer is unavailable.
**Acceptance:** metadata table reflects uploaded top-level targets; metadata
queries gate downloads correctly.

### W4. Write-proxy / read-proxy clients
**Why:** `--experimental_remote_analysis_write_proxy`
(SkycacheStorageWriteProxyService) and `--experimental_analysis_cache_service`
(read proxy) are referenced as locators only; no client talks to them.
**Work:** Implement the proxy transports and select between direct and proxied
paths per the configuration matrix documented in
`RemoteAnalysisCachingOptions.java:176-183`.
**Acceptance:** proxied upload and download both function; config validation
rejects incompatible combinations with clear errors.

### W5. A reference/open-source services supplier
**Why:** Today the only DI point yields in-memory. For OSS usability and
hermetic integration testing, provide a supplier that wires W1–W3 to a real
(local-disk or gRPC remote-cache) backend.
**Work:** New `RemoteAnalysisCachingServicesSupplier` implementation selectable
without source overrides (module flag / `--experimental_*`), plus a disk-backed
`FingerprintValueStore` for single-machine reuse.
**Acceptance:** `bazel build --experimental_remote_analysis_cache_mode=upload`
then a clean `download` build reuses analysis on one machine with no internal
code.

## 5. Work items for CORRECTNESS

Incorrect analysis cache hits are worse than no caching — they can silently
produce wrong builds. These items are non-negotiable for GA.

### C1. Upload only freshly-computed, change-unaffected nodes
`SelectedEntrySerializer.upload` carries
`TODO: b/371508153 - only upload nodes that were freshly computed by this
invocation and unaffected by local, un-submitted changes`
(`SelectedEntrySerializer.java:343`). Uploading nodes contaminated by
uncommitted local edits poisons the shared cache.
**Work:** track node provenance (freshly computed this invocation vs. loaded)
and exclude nodes derived from dirty/local state; gate on a clean workspace
snapshot (`WorkspaceInfoFromDiff.getSnapshot()`).
**Acceptance:** a build with local modifications never uploads entries that
encode those modifications; verified by a dirty-workspace integration test.

### C2. Cache-key completeness audit (`FrontierNodeVersion`)
The key combines trimmed config checksum, install MD5, Starlark semantics
fingerprint, evaluating version, snapshot, and a testing distinguisher
(`RemoteAnalysisCacheFactory.java:163-171`). Any analysis-affecting input not
folded into this key is a correctness hole.
**Work:** systematically audit analysis inputs (e.g. `--action_env` that affects
analysis, platform/toolchain resolution inputs, repository contents,
`PROJECT.scl` semantics, `--define`s, environment that leaks into Starlark)
and prove each is either in the key or provably analysis-irrelevant. Document
the closure.
**Acceptance:** a documented, test-enforced list of analysis inputs and their
key membership; fuzz/diff test that changing any of them changes the key.

### C3. `--check_visibility` priming safety
Download mode recomputes config checksums as if `--check_visibility=true`
(`RemoteAnalysisCacheFactory.trimConfigurations`) so download can reuse entries
even when the user passed `--check_visibility=false`. This is only safe *if
failures are never cached*. **Work:** assert/enforce the no-cached-failures
invariant and add a regression test that a visibility error is never served
from cache.

### C4. Invalidation correctness across versions and clients
`AnalysisCacheInvalidator.lookupKeysToInvalidate`
(`AnalysisCacheInvalidator.java:86-119`) invalidates everything on
missing/changed version and short-circuits when the `ClientId` matches.
Open issue `b/439857268`: previous version can be unexpectedly unset after an
interrupted build (currently handled by "invalidate everything", safe but
slow). **Work:** root-cause the unset-version path; ensure server state
(`RemoteAnalysisCachingServerState`,
`SkyframeExecutor.syncRemoteAnalysisCachingState`) is persisted/restored
correctly across interrupts; add tests for interrupt-then-rebuild.

### C5. Missing-fingerprint bail-out semantics
`--experimental_analysis_cache_bail_on_missing_fingerprint` and
`bailOutDueToMissingFingerprint()` exist for the case where metadata promised a
hit but a referenced fingerprint is absent mid-deserialization. **Work:** define
and test the exact recovery (full local fallback vs. partial), and make sure a
mid-stream bail leaves Skyframe in a consistent state.

### C6. Determinism of serialization
`FileOpNodeMemoizingLookup` notes determinism caveats
(`FileOpNodeMemoizingLookup.java:208`, `:224`). Non-deterministic serialized
bytes weaken dedup and can mask key bugs. **Work:** make frontier traversal and
serialized output deterministic; add a byte-stability test (serialize twice,
compare).

## 6. Versioning: mapping VCS state to monotone Longs

Cross-machine correctness rests entirely on a **version number**: a writer tags
each cached node with the version at which it was computed, and a reader decides
a hit is valid only by comparing versions. This section captures the contract
that mapping must satisfy, what is stubbed today, and the concrete work to make
it real for git (and other external VCS).

### 6.1 Why a monotone total order is required (the consumer contract)

`VersionedChanges` (`VersionedChanges.java:21-65`) does **range arithmetic over
a total order**. Each cached node tracks:

- **MTSV** (max transitive source version) — lowest version at which the node
  has its current value; the canonical lower bound of validity.
- **VH** (validity horizon) — highest version at which the node is *known*
  valid.
- **VC** (client version) — the version the *reader* is synced to.

The validity rule is: *a cached node is valid for a reader at VC iff none of its
file/listing dependencies changed in the range `(VH, VC]`*, implemented as a
binary search `findMinimumVersionGreaterThanOrEqualTo(versions, VH + 1)`
(`VersionedChanges.java:128-182`) over a sorted `int[]` of versions at which each
path changed. This is correct **only** if versions satisfy:

1. **Monotone:** a state causally later than another has a strictly larger
   number (descendant commit ⇒ larger version).
2. **Stable / global:** the same committed state yields the same number on every
   machine, so a writer's MTSV and a reader's VC are comparable. (Rules out
   wall-clock time and purely local counters for *committed* state.)
3. **Representable:** per-path change versions are stored as `int[]`, so they
   must fit `int` even though the workspace version is a `long`.

### 6.2 What exists today (stubbed)

Two interfaces define the boundary; both return sentinels in OSS:

- `WorkspaceInfoFromDiff` (`WorkspaceInfoFromDiff.java`) — per-invocation
  workspace version: `getEvaluatingVersion()` (default `Long.MIN_VALUE`) and
  `getSnapshot()` (default empty). Both carry
  `TODO: b/367284400 - handle this for external version control systems`.
- `LongVersionGetter` (`LongVersionGetter.java`) — per-path "when did this last
  change": `getFilePathOrSymlinkVersion`, `getDirectoryListingVersion`,
  `getNonexistentPathVersion`, with sentinels `CURRENT_VERSION = Long.MAX_VALUE`
  (changed in the live client) and `MINIMAL = -1` (never existed / never
  changed).

A concrete `DiffAwareness`/VCS plugin is expected to supply these; none ships
here, so every reader currently looks unversioned and the `VersionedChanges`
machinery cannot safely decide hits.

### 6.3 V1. Git hash → monotone `long` (baseline version)

A git SHA is content-addressed and **unordered**, and history is a DAG, so the
hash itself cannot be the version. The version must be the commit's **position
in a linearized history**:

- **First-parent / mainline depth** (`git rev-list --count`, a commit index):
  on a linear trunk this is a perfect monotone total order (the analog of a
  Piper CL number, which is why the internal implementation maps cleanly).
- **Commit generation numbers** (commit-graph generation / topo order) with a
  deterministic tiebreak when DAG-awareness is needed. Pure topological order is
  not *total* across branches, so constrain to a tracked mainline or use a
  server-assigned sequence (submit queue / CI) recorded in a
  `commit_sha → seq` side table for stability across rebases/merges.

Per-file versions are then "the trunk sequence number of the commit that last
touched this path," feeding `registerFileChange(path, version)` and
`getFilePathOrSymlinkVersion`.

**Work:** implement a git-backed `WorkspaceInfoFromDiff` +`LongVersionGetter`
(or `DiffAwareness`) producing versions that satisfy §6.1 (1)-(3); choose and
document the linearization strategy and its behavior on merges/branch switches.
**Acceptance:** the same committed tree yields identical versions on two
machines; a writer at version `N` produces entries a reader synced at `≥ N`
reuses; changing a single file advances exactly that path's version.

### 6.4 V2. Snapshot identity within a baseline

A **baseline** is a committed revision (the monotone long); a **snapshot** is
the developer's *uncommitted* edits on top of it. Uncommitted state has no place
in the global depot order and is modeled in two complementary ways:

- **As identity — `ClientId`** (`ClientId.java`):
  `SnapshotClientId(String workspaceId, int snapshotVersion)` for a dirty client
  vs. `LongVersionClientId(long evaluatingVersion)` for a clean synced client.
  `snapshotVersion` is *"a monotonically incrementing number of the snapshot"* —
  a **local** counter bumped as the working tree's edits change (monotone only
  within that `workspaceId`). `RemoteAnalysisCacheFactory` selects via
  `getSnapshot().orElse(new LongVersionClientId(evaluatingVersion))`
  (`RemoteAnalysisCacheFactory.java:142-145`). The `ClientId` is the
  "cache the client's own state" key for invalidation: in
  `AnalysisCacheInvalidator.lookupKeysToInvalidate`
  (`AnalysisCacheInvalidator.java:100-112`), an unchanged `FrontierNodeVersion`
  plus an equal `ClientId` ⇒ invalidate nothing; a bumped snapshot ⇒ re-probe
  only the changed keys, preserving the rest of the baseline's cache.
- **As change records — sentinels:** locally-edited files are injected as
  "changed *after* everything in the depot" so they always win invalidation but
  never collide with committed versions. `VersionedChanges` registers
  `clientFileChanges` at `CLIENT_CHANGE = Integer.MAX_VALUE - 1`
  (`VersionedChanges.java:80, 96-100`), and `LongVersionGetter.CURRENT_VERSION
  = Long.MAX_VALUE` plays the same role per path.

**Work:** populate `SnapshotClientId` (stable `workspaceId`, monotone local
`snapshotVersion`) and the client file-change list from the git working tree
(staged + unstaged + untracked), and wire them through to `VersionedChanges`.
**Acceptance:** with local edits, only locally-touched paths are re-validated
against the baseline cache; two successive builds with no new edits report an
equal `ClientId` and invalidate nothing; committing the edits transitions the
client to a clean `LongVersionClientId` at the new baseline.

### 6.5 V3. Int-width and overflow safety

Workspace versions are `long` but per-path change versions live in `int[]`
(`VersionedChanges`), and `CLIENT_CHANGE`/`NO_MATCH` consume the top of the
`int` range. **Work:** define the narrowing from depot `long` to the stored
`int` (offset/rebasing scheme, or widen storage), and prove it cannot collide
with the reserved sentinels or overflow on long-lived repositories.
**Acceptance:** a repository whose trunk sequence exceeds `int` range still
produces correct, sentinel-safe comparisons.

This versioning work is a prerequisite for genuine cross-machine hits and is
tightly coupled to C1 (clean-node upload), C2 (key completeness), and C4
(invalidation). It belongs in **Phase 1**.

## 7. Work items for SPEED

### S1. Selective upload (also a correctness item, C1)
Avoid re-uploading unchanged nodes every build; only upload deltas. Directly
reduces write volume and the synchronous upload tail.

### S2. Download-path parallelism & batching
The retriever is restart-driven and async-capable. Ensure lookups are batched
(reuse `getMaxBatchSize`) and that Skyframe restarts from cache misses do not
serialize what could be parallel fetches. **Work:** measure restart counts and
batch fan-out; add prefetching of likely-needed fingerprints.

### S3. Net build-time benchmark harness
There is no end-to-end "did Skycache actually make the build faster" benchmark.
**Work:** add a benchmark comparing cold local analysis vs. warm download across
representative graphs; track analysis time, RPC bytes, hit rate, and the
serialization-tail contribution. Make hit-rate and latency buckets (already in
`Stats`) first-class dashboards.

### S4. Serialization-tail overlap
`UPLOAD` work happens largely post-build; `ASYNC_UPLOAD`/`BIDI` overlap it with
the build (`SkycacheUploadClient`). **Work:** confirm async upload does not
contend with execution; bound writer concurrency
(`getMaxWriteConcurrency`/`getTargetWriteConcurrency`) and verify the tail is
hidden under execution.

### S5. Memory headroom (`--experimental_skycache_minimize_memory`)
Currently marked *"DO NOT USE… does not work with every target"*
(`RemoteAnalysisCachingOptions.java:236`). Discarding package/analysis values
post-analysis gives writers headroom but is unstable. **Work:** make value
discarding safe for all target kinds (it touches
`b/390533627`-style executor concerns in `RemoteAnalysisCacheDeps.java:134`);
graduate the flag.

## 8. Observability, UX, and stabilization

- **O1. Flag graduation & docs.** Every option is `UNDOCUMENTED`
  (`RemoteAnalysisCachingOptions.java`). Define the supported surface, write
  user docs, and graduate the stable flags out of `experimental_`.
- **O2. Clear fallback messaging.** Today `DOWNLOAD` silently warns and falls
  back when the client is null. Make fallbacks observable (BEP + metrics counter
  "skycache_fallback_reason").
- **O3. Hit-rate / correctness telemetry.** Promote `Stats.matchStatus`,
  `MissReason`, and fingerprint-store stats into BEP and the metrics proto for
  monitoring at scale.
- **O4. Failure-mode UX.** Metadata-write failures are warnings; ensure all
  non-fatal degradations are clearly distinguished from incorrect results.

## 9. Testing strategy

1. **Persistent backend integration tests** (replace in-memory-only coverage):
   upload on one server instance, download on another.
2. **Correctness/differential tests:** build target locally → upload; clean +
   download; assert identical actions/outputs and zero re-analysis. Mutate each
   analysis input from C2 and assert a cache miss.
3. **Dirty-workspace test (C1):** local edits never uploaded.
4. **Interrupt/resume test (C4):** interrupted upload build, then rebuild,
   yields correct invalidation.
5. **Visibility test (C3):** failures never served from cache.
6. **Benchmark suite (S3):** tracked over time.
7. **Determinism test (C6):** stable serialized bytes.
8. **Versioning tests (V1-V3):** same committed tree ⇒ identical versions across
   machines; single-file edit advances exactly one path's version; dirty
   workspace re-validates only touched paths and reports a stable `ClientId`
   across no-op rebuilds; `int` narrowing is sentinel-safe and overflow-safe.

## 10. Suggested phasing

- **Phase 0 — Make it real (functional):** W1, W2, W5. A persistent store plus a
  working lookup client unlock genuine cross-invocation hits.
- **Phase 1 — Make it safe (correctness):** V1-V3 (versioning) and C1, C2, C3,
  C4 with the testing in §9. Cross-machine hits are impossible without V1-V3, so
  they gate this phase. Do not enable by default before this phase passes.
- **Phase 2 — Make it fast:** S1, S2, S3, S4; W3/W4 for proxy/metadata
  deployments.
- **Phase 3 — Productionize:** S5 (memory), O1–O4 (flags/docs/telemetry),
  C5/C6 hardening.

## 11. Key references

- `src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis/`
  — `RemoteAnalysisCacheFactory.java`, `RemoteAnalysisCacheManager.java`,
  `RemoteAnalysisCacheDeps.java`, `RemoteAnalysisCachingServicesSupplier.java`,
  `RemoteAnalysisCacheClient.java`, `RemoteAnalysisMetadataWriter.java`,
  `SelectedEntrySerializer.java`, `FrontierSerializer.java`,
  `AnalysisCacheInvalidator.java`, `RemoteAnalysisCachingOptions.java`,
  `RemoteAnalysisCacheMode.java`.
- `src/main/java/com/google/devtools/build/lib/skyframe/serialization/`
  — `SkyValueRetriever.java`, `SerializationModule.java`,
  `InMemoryFingerprintValueStore.java`, `FingerprintValueStore.java`.
- `src/main/java/com/google/devtools/build/lib/skybridge/SkybridgeInterface.java`
  — the SC/LC boundary annotation.
- Versioning: `VersionedChanges.java`, `ClientId.java`,
  `src/main/java/com/google/devtools/build/lib/skyframe/WorkspaceInfoFromDiff.java`,
  `src/main/java/com/google/devtools/build/lib/versioning/LongVersionGetter.java`,
  `FrontierNodeVersion.java`.
- `src/main/java/com/google/devtools/build/lib/buildtool/BuildTool.java`
  — upload/download orchestration and metadata write.
- Tests under
  `src/test/java/com/google/devtools/build/lib/skyframe/serialization/analysis/`.

### Open TODOs referenced

- `b/358347099` — persistent fingerprint store (`SerializationModule.java:96`).
- `b/371508153` — upload only freshly-computed/clean nodes
  (`SelectedEntrySerializer.java:343`).
- `b/425247333` — metadata db insertion/querying
  (`RemoteAnalysisCachingOptions.java:210`).
- `b/439857268` — unset previous version during invalidation
  (`AnalysisCacheInvalidator.java:92`).
- `b/390533627` — executor choice for fingerprint work
  (`RemoteAnalysisCacheDeps.java:134`).
- `b/364831651` — determinism / traversal scaling
  (`FileOpNodeMemoizingLookup.java`, `VersionedChanges.java:89`).
- `b/367284400` — version derivation for external version control systems
  (`WorkspaceInfoFromDiff.java:24,29`).
</content>
</invoke>

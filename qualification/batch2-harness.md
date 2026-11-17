# Qualification batch 2 — dormant native-authoring harness

**Authored only, not run.** Source set `qualificationSupport` is separate from `main`, `test` and historical `probe`, with mod namespace `better_spaceflight_qualification`. Its annotated `QualificationNativeGameTests` class is **not registered**; missing real recovery/dispatch causes an explicit `BLOCKED` refusal if called, never `helper.succeed()`. There is no qualification Forge run configuration or Gradle task. Existing `gameTestServer` remains `better_spaceflight_probe` and must not be used as qualification. No production compatibility source or original VS JAR was altered.

## Why receipt-only CLI, not a Gradle task

The configured ForgeGradle mapped JAR currently fails dependency verification before unit execution: expected SHA-256 `57930057043b9da110644d454c8efc3a247cb213bb310a1e2a46dbac2743f194`, generated SHA-256 `5156e6602718bf1dd47588b62b424687b6170a89a6b72d27e0a851319f912670`. Gradle can fail during configuration **before** a task can print/write a receipt. To guarantee a machine-readable preflight outcome, `scripts/qualification-native-preflight.py` is a standalone pure Python, receipt-only tool. Do not regenerate locks/verification metadata, repurpose `runGameTestServer`, package or run a native server to work around this.

**Future review-only template — do not execute as a native attempt now:**

```sh
# Candidate digest MUST come from separately reviewed frozen inputs; never guess or reuse a receipt UUID.
python3 -B scripts/qualification-native-preflight.py \
  --attempt-id NEW_CANONICAL_LOWERCASE_UUID4 \
  --candidate-sha256 REVIEWED_64_LOWERCASE_HEX_SOURCE_DIGEST \
  --historical-probe-sha256 REVIEWED_64_LOWERCASE_HEX_PROBE_PIN
```

The command prints exactly one JSON object with `schema=1`, `status="blocked"`, `attempt_id`, `reason[]`, candidate/original-VS/Forge digests and `receipt_path`. Exit code **2** is blocked, or **75** for nonblocking project-lock contention; neither is a failed GameTest or a pass. With a valid new ID and safe repository paths, a single immutable receipt is created under `.tmp/qualification-receipts/<uuid>.json` using `O_EXCL`, file+directory fsync and no overwrite; on unsafe/invalid/contended preflight the JSON stdout receipt remains available even when no disk file can be safely created. The tool **never creates** `instances/qualification/<uuid>/native`, `world`, `saves`, any test fixture or a server process. An existing receipt OR any attempt directory is a hard blocker. The `instances` ancestors and `.tmp/qualification-receipts` must be real non-symlink project directories; lock and receipt file descriptors use no-follow operations.

The tool obtains the same project-local `.tmp/spaceflight-runtime.lock` with nonblocking `flock`, verifies the open lock inode is a regular file at that exact path and emits a JSON `blocked` receipt with exit75 on contention (stdout only, no unsafe disk write). It hashes frozen path+byte content from **all** `src/main`, `src/qualificationSupport`, `src/test`, `tests`, `qualification`, `scripts`, `gradle` (including verification metadata and wrapper JAR), plus Gradle build/settings/properties/locks and wrapper launchers. Historical `src/probe` is separately hashed and compared to a *reviewed pin* but is **excluded from production candidate qualification**; its historical pass remains nonqualifying. It compares the supplied candidate digest, checks the original pinned VS 2.4.11 JAR SHA-256 `f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53` at the known local path (missing blocks; never downloads), and records the Forge mapped hash mismatch. Crucially, even with otherwise matching inputs it **still blocks** on absent production connected-set dispatch plus complete full-world backup/restore and actor/chunk closure. An arbitrary `--ready`, synthetic backend, or historical probe success cannot unlock it.

## Real-oracle requirements for a future, separately approved runner

A later approved native runner (not present here) needs a **new** disposable UUID attempt root, cold-start owner/artifact checksum and opt-in gates, actual loaded FULL/on-disk chunk enumeration, preexisting target occupancy refusal, verified durable **source+destination world backup**, controlled failure/restart and authenticated production dispatch. For every occupied constraint-connected member it must observe original ship IDs, exact logical/backend joint IDs, native body references/inertia/pose/linear and angular velocities at the owner physics boundary, listener/callback ownership, dynamic coast and gravity control. It must compare raw block/BE/cargo/ForgeCaps/scheduled-tick identity and deadlines, every actor/player/seat/riding root, destination and source after each hop, and all five exact-route descriptors (including Twilight still blocked until genuinely safe). A refusal negative control is not a successful Twilight route.

If human runtime authorization and trusted inputs later exist, the **separately reviewed** runner should cap each real attempt at ≤230 seconds plus ≤20 seconds owned cleanup; preserve complete failed world, logs, source hashes, process exit, per-scenario evidence and fault ordering; forbid auto retries and never reset/reuse a friends' or previous attempt world. A server exit, GameTest summary or past static probe pass alone never marks `passed`. Two independent real clients, screen evidence and cold restart are batch-3/manual obligations, not achieved by this skeleton.

## Validation limit

Pure static Java and Python tests can check source-set isolation, refusal-only skeleton, safe blocked receipt and alias/duplicate/lock/symlink behavior. Direct Java compilation against cached mapped Forge is **syntax-only** if later performed; it is not trusted Gradle compilation, transformed-Mixin verification, JNI/native physics, world save, visual client or end-to-end qualification. Batch-3 evidence validator is required before any non-blocked machine result is accepted.

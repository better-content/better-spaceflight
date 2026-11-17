# Better Spaceflight qualification suite — author-only batches 1–3

**State:** source/test design, not a runnable production transfer qualification. No Minecraft, native/JNI, transformed Mixin, client, world, GameTest, JAR or deployment was run for this suite. The human-approved scope is **Spaceflight only**; no pack-wide acceptance is implied. `src/probe` and its `gameTestServer` are historical destructive diagnostics, **never** a production qualification. The current production route, complete world backup/restore, whole actor/chunk closure, powered-listener continuity and authenticated gate grants do not exist. All 37 mandatory native/fault/client scenarios in `QualificationScenarios` initially return **`blocked`**. A historical static probe pass cannot change that.

## Immutable evidence and stage boundary

- Original VS 2.4.11 JAR SHA-256 `f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53`. Keep original bytes, pinned `FN`/`FU`, Forge locks and mapped-hash mismatch untouched. Generated mapped Forge expected `57930057043b9da110644d454c8efc3a247cb213bb310a1e2a46dbac2743f194`, actual `5156e6602718bf1dd47588b62b424687b6170a89a6b72d27e0a851319f912670`; Gradle verification fails **before** suite execution. Direct compilation/pure tests do not satisfy this release gate.
- Previous accepted dormant compatibility checkpoint is source-only: owner-bound PREPARING/CANCELLED journal with an unissuable game no-mutation permit, default-OFF hooks, Eureka veto and no production dispatch, restore, marker grant or travel readiness. `teleportShip` destructively copies active chunks to destination then clears source; whole-world recovery and safe target occupancy remain unproved. No source-level fake should promote these prerequisites.
- Author-only lifecycle (not executed): **batch 1** catalog/unit contract and honest result statuses; **batch 2** separate `qualificationSupport` source set, independent namespace and dormant native GameTest skeleton plus receipt-only Python preflight (details in [`batch2-harness.md`](batch2-harness.md)); **batch 3** nonshipping fault/restart fixture spec, two-real-client manual procedure and always-blocked evidence validator (authored only; no execution). No batch-2/3 scenario may run until a separate human runtime approval, trustworthy Forge/JAR inputs, authentic production recovery/dispatch, and fresh world/lock preflight. Do not retrofit `src/probe` or its existing `gameTestServer`/`probeGate`/marker. No qualification Gradle run config/task exists: Forge configuration cannot guarantee the required BLOCKED receipt.

## Existing 25 `src/test/java` files (read-only inventory)

| File (under `com/bettercontent/spaceflight/`) | Methods | Existing evidence class; remaining qualification gap |
| --- | ---: | --- |
| `client/GateMarkerGeometryTest.java` | 3 | Pure four-region geometry and hard-false grant; no real visual/network grant. |
| `probe/MotionChecksTest.java` | 8 | Historical synthetic coast oracle only; the paced real native coast failed `vx=10/dx=0`. |
| `probe/ObserverTransportTest.java` | 4 | Historical synthetic Netty sink; not packet delivery or real login. |
| `probe/TickPacerTest.java` | 9 | Pure pacing logic; native coasting still failed with pacing. |
| `travel/SeatedPilotContractTest.java` | 2 | Pure/pinned pilot authority; no real two-client mounted handoff. |
| `travel/ServerOwnerPreflightTest.java` | 5 | Startup journal scan and terminal-but-unbound behavior; no actual cold restart. |
| `travel/ServerTravelPolicyTest.java` | 14 | Pure four destinations/ascent nonce/cooldown; no production readiness/provider. |
| `travel/SpaceDimensionResourcesTest.java` | 3 | Source JSON checks; no actual world/resource lifecycle. |
| `vs/AssemblyTranslationTest.java` | 8 | Geometric-offset pure checks; no whole connected native assembly. |
| `vs/SectionLayoutTest.java` | 4 | Equal layout/Nether negative control; Twilight route still **blocked**. |
| `vs/compat/Eureka163ArtifactGuardTest.java` | 4 | Original/mapped byte guard; no Forge transform/loader or powered fidelity proof. |
| `vs/compat/EurekaHookContractTest.java` | 4 | Static opt-in hook contract; no live listener reconstruction. |
| `vs/compat/ForgeOwnerContractTest.java` | 2 | Static owner/hook signatures; no actual server/physics thread. |
| `vs/compat/JointDefinitionCodecTest.java` | 2 | Detached fixed/distance joint DTO; no native same-ID transfer. |
| `vs/compat/JointMigrationJournalTest.java` | 7 | Durability/owner-phase pure FS tests; not a world backup. |
| `vs/compat/PhysicsThreadStopProofTest.java` | 2 | Synthetic thread stop; not asynchronous native close. |
| `vs/compat/PinnedCallScopeTest.java` | 4 | Pure scoped suppression; no transformed Mixin execution. |
| `vs/compat/ReservationRegistryTest.java` | 28 | Pure fakes for freeze/release/cancellation; no actual physics or game mutation. |
| `vs/compat/Vs2411ArtifactGuardTest.java` | 4 | Original artifact/hash checks; not runtime Forge trust. |
| `vs/compat/Vs2411BytecodeContractTest.java` | 3 | Bytecode callsites only; not live FN/FU graph. |
| `vs/compat/recovery/CaptureAdapterContractTest.java` | 4 | Static codec/actor/chunk contract; no restorable world proof. |
| `vs/compat/recovery/CaptureEnvelopeTest.java` | 7 | Detached bounded evidence/worker lease; explicitly incomplete capture. |
| `vs/compat/recovery/ForgeChunkCapabilityEvidenceTest.java` | 4 | Synthetic selected-capability omission checks; arbitrary providers unqualified. |
| `vs/compat/recovery/SemanticCaptureComparatorTest.java` | 4 | Narrow bookkeeping normalization; no future-state quiescence. |
| `vs/compat/recovery/SerializedTickIdentityTest.java` | 2 | Synthetic tick writer identity; no live destination tick restoration. |

The three `probe/*Test.java` files compile historical `src/probe` types; do not include them in a new production native pass. Direct isolated JUnit-API method counts differ from the full Gradle test source set. No existing assertion was removed or weakened.

## Historical `src/probe` inventory (11 classes; preserved unchanged)

| Class | Historical purpose / limitation |
| --- | --- |
| `TransferGameTests` | Destructive one-ship static round trip via `Vs2411Adapter.teleportForProbe`, previously static subgate pass; not occupied connected production transport. |
| `DynamicGameTests` | Synthetic loaded observer, gravity and moving coast; most recent coast failed despite paced ticks. |
| `ProbeRig` | Gold/copper/iron 12-block sentinels, cargo/fuel and diagnostic record; intentionally test-only. |
| `ProbeCommands` | Operator begin/hop/status/remount shortcuts; not pilot confirmation or recovery. |
| `ProbeMod` | Historical probe mod/test registration and blocks. |
| `ProbeGate` | Static/dynamic historical gate selection. |
| `ProbeClock` | Probe-scoped clock/event pacing. |
| `ProbeObserver` | Synthetic bound server player, not a real client. |
| `ObserverTransport` | Discard-only Netty channel; no packet delivery. |
| `MotionChecks` | Pure motion sample thresholds. |
| `TickPacer` | Pure cadence helper. |

`src/probe/resources` contains its own mod metadata, 1.20.1 empty structure and old probe-only space dimension/VS zero-gravity parameter; those do **not** describe normal production gravity. `tests/test_probe_clock.py` and `tests/test_probe_resources.py` are also historical packaging/pacing checks, not production transfer acceptance. `build.gradle.kts` currently wires `gameTestServer` to `better_spaceflight_probe`, compiles probe sources and expects exactly one probe marker. This suite must never reuse that task or repurpose `src/probe`.

## Mandatory scenario matrix (all status `blocked` in batch 1)

The exact machine-readable list, oracle text and evidence requirement live in `QualificationScenarios.java`; IDs are fixed for future evidence joins. **`native_` (22):** `original_artifacts`, `default_off`, `startup_checksum`, `connected_component`, `joint_identity`, `physics_liveness`, `pose_velocity` (including inertia), `frame_boundary`, `loaded_coverage`, `unloaded_claims`, `block_entity_cargo`, `capability_ticks`, `actor_closure`, `destination_empty`, `motion_gravity`, `equal_layout_refusal`, `ascent_space`, `overworld_route`, `wastelands_route`, `abyssal_route`, `twilight_route`, `addon_veto`. The layout-refusal safety check cannot satisfy the separately mandatory Twilight **route**, which remains `blocked` until unequal-layout transfer is safe.

| Mandatory route ID | Exact source → destination | Required destination/return oracle | Current status |
| --- | --- | --- | --- |
| `native_ascent_space` | one exact surface allowlist ID → `better_spaceflight:space` | Pilot-confirmed Y512 ascent; original complete connected set/joints/actors/cargo and moving pose/velocity/inertia at arrival; no return inferred from arrival | `blocked` |
| `native_overworld_route` | `better_spaceflight:space` → `minecraft:overworld` | Bound pilot confirmation, same connected set/actors/cargo/motion at destination and return/landing without duplicate craft | `blocked` |
| `native_wastelands_route` | `better_spaceflight:space` → `fallout_wastelands_:wastelands` | Bound pilot confirmation, same connected set/actors/cargo/motion at destination and return/landing without duplicate craft | `blocked` |
| `native_abyssal_route` | `better_spaceflight:space` → `better_abyssal_city:abyssal` | Bound pilot confirmation, same connected set/actors/cargo/motion at destination and return/landing without duplicate craft | `blocked` |
| `native_twilight_route` | `better_spaceflight:space` → `twilightforest:twilight_forest` | Same full route/landing evidence required but unequal layout currently vetoes; refusal only proves safety negative, never successful route | `blocked` |

Route keys/destinations are exact, case-sensitive and cannot be inferred from marker labels. A gate being visible or a region being defined does not satisfy a route. All four surface exits plus the ascent must eventually pass on the same frozen candidate; Twilight must remain **explicitly blocked**, never silently omitted or mislabeled successful transit.

**`fault_` (10):** `before_mutation`, `preparing_ambiguous`, `cancel_no_mutation`, `destination_copy`, `source_clear`, `partial_component`, `joint_restore`, `release_fsync`, `restart_reconcile`, `restore_integrity`. Every destructive fault requires retained failed world, verified whole-world backup, both target/source digests, physics-owned quarantine and manual reconciliation; a joint journal or reverse `teleportShip` is not a restore.

**`client_` (5):** `four_markers`, `pilot_confirmation`, `mounted_tracking`, `concurrent_users`, `restart_persistence`. Require two **real** clients, visual and server-identity evidence. Marker source is dormant/hard-false until an authenticated server grant exists; no fake WAIT ticket, diagnostic preview or simulated confirmation can satisfy this.

**Status semantics:** `passed` = exact expected assertion with trustworthy matching candidate/evidence and validated provenance; `failed` = an executed assertion with retained authentic failure evidence; `blocked` = a missing/unsafe prerequisite or ambiguous inputs; `not_run` = prerequisite proven but scenario deliberately not executed. Missing scenario/evidence, unknown status, hash/candidate mismatch, or synthetic `passed` yields `blocked`, never pass. `QualificationAggregate` intentionally cannot accept **any self-reported non-blocked outcome** yet: a 64-character digest is not proof that a run occurred. A separately reviewed validator must authenticate actual candidate/evidence and prerequisites before `failed`, `not_run` or `passed` can enter a final report. To qualify, **every mandatory scenario must pass on the same frozen candidate**, including ascent and all four actual surface-exit routes (Twilight cannot pass by refusal); neither historical probe evidence nor unit results substitute for native/client outcomes.

## Batch 1 architecture and next gates

`QualificationScenarios` and `QualificationAggregate` are package-private **test-source-only** data/guards with no Minecraft or Forge imports. `QualificationScenariosTest` asserts exact four policy IDs, coordinates, activation radius, Y512 ascent and 37 required pending descriptors plus exact route/alias rejection; it does not claim visible markers. `QualificationAggregateTest` refuses synthetic all-pass, missing, duplicated, unknown or mixed-hash reports. These are new **pure unit tests only**. Batch 2 authors an independent namespace/source set, **unregistered** GameTest skeleton, and pure receipt-only preflight with fresh UUID, fail-fast project lock and source/original VS/Forge hash snapshot. It creates no world/test files and invokes no native server. A real bounded runner/failed-world retention can only be separately reviewed after genuine backend readiness and runtime approval. Batch 3 authored the future fault/restart fixture specification [`fault-restart-fixtures.md`](fault-restart-fixtures.md), independent real-client manual procedure [`two-real-client-procedure.md`](two-real-client-procedure.md), 37-ID machine roster [`mandatory-scenarios.json`](mandatory-scenarios.json) and assertion→tier/provenance contract [`evidence-schema.md`](evidence-schema.md). `scripts/qualification-evidence.py` verifies authored BLOCKED document structure and reviewed candidate/probe pins but **cannot promote any executed result**: without a trustworthy producer it always prints `blocked` and `NOT_EXECUTABLE` (even when `document_complete=true`). This is not a runtime test or release qualification. No native fault hook, destructive caller, client UI, real readiness provider or Gradle task was added. Future runtime commands are review-only templates until authorization and prerequisites exist.

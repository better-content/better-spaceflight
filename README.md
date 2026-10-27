# Better Spaceflight source — feasibility stage

Java/Forge subject repository for the isolated lab. Read `../../AGENTS.md` and `../../README.md` first. This is a new uncommitted independent repository, not a fork of the main pack or VS. Current code is a version-pinned adapter and a separate nonshipping diagnostic probe; survival gameplay is not implemented.

## Builds

Use `scripts/gradle-lab.sh`: existing Java17, independent project Gradle home/build/temp, two workers and fail-fast lab `.tmp/spaceflight-runtime.lock` for actual same-project conflicts. Per user preference/root ACK, independent bounded functional tests may run alongside other isolated projects without a home-wide grant queue. Respect actual shared-state conflicts, resource pressure and explicit benchmark quiet windows. Exit75 means project contention; never queue or bypass it. Daemon flags explicitly cap2GiB/two active processors; runtime qualification remains230s+20s cleanup, no automatic retry.

```sh
# Lightweight probe packaging guards; these do not start Minecraft:
python3 -B -m unittest discover -s tests -v
# Existing locks/checksums are present; do not regenerate to hide drift:
./scripts/gradle-lab.sh test probeClasses
# Stage main only; DOES NOT include probe classes/resources or perform acceptance:
./scripts/gradle-lab.sh stageRuntimeJar
```

The wrapper is Gradle8.8, independently downloaded from its official v8.8.0 sources; JAR SHA256 `cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`, matching the official checksum. Distribution SHA256 is checked in. ForgeGradle6.0.54 is fixed. First-resolution Maven metadata/checksums require review; never rewrite verification metadata to bless an unexplained mismatch.

## Recorded results

- First actual Forge build: main/probe compiled, 4/4 JVM tests PASS, 3m4s; evidence `../../generated/evidence/forge-compile-5c85610c-8f51-45e1-b239-518900aed56e/`.
- First native invocation: EXIT1 in53s; missing probe `pack.mcmeta` excluded the datapack, so the empty structure failed to load **before test execution**. No transfer result. Failed world/crash retained. Evidence `../../generated/evidence/native-subgate-48e0d764-851d-4220-9485-6eba8638d859/`.
- Added the probe's own metadata; 3/3 lightweight packaging/layout guards PASS. Second native invocation confirms resource loading but **1/1 authored required test FAILED during initial assembly**, EXIT1/43s; no transfer reached. Evidence `../../generated/evidence/native-subgate-7d02ffd5-ba6f-4fcd-b07e-336c152dfad2/`. Normal failed-test shutdown and complete process cleanup confirmed13:32:15UTC.
- Read-only saved-world and release-bytecode diagnosis found the adapter used a COM-mutated `fromCenter`, not the geometric placement origin. Approved bounded correction now captures the original bounding-box center and validates exact integer offsets; no floor/epsilon, no changed test oracle/deadline. **12/12 standalone JVM checks PASS** (8 new coordinate regressions+4 existing layout tests), evidence `../../generated/evidence/assembly-correction-244c44ba-9444-4795-a297-d223086d939b/`. At that pure-helper checkpoint full Forge compilation/native verification were NOT RUN. Standard dev-refmap flags were already exercised. No automatic heavy retry or checksum regeneration.
- Third attempt, separately authorized combined qualification: **actual main/probe recompilation +12/12 Gradle JVM tests +1/1 static native GameTest PASS**, EXIT0/48s, ship4/hops2/12blocks/3BEs. Evidence `../../generated/evidence/native-qualification-7ac851d8-dfa7-48d2-85a3-29befb84ea7d/`. Original native assertions/deadlines unchanged; exact input hashes checked PRE/POST. Process/global-lock clear confirmed13:52:56UTC. **30 late missing-ship physics-frame warnings and1 assembly chunk-ticking timeout remain unresolved.** Loaded/dynamic native lifecycle, passengers, real clients and process restart remain NOT_EVALUATED. No follow-on run, staging or deployment authorized; the static subgate is not full acceptance.

- First dynamic qualification `e223fbcc-34ed-4c97-9c00-468a1ff174a4`: new probe/test compilation and **20/20 Gradle JVM checks PASS**, but **exactly one dynamic native test FAILED**, EXIT1/47s. Vanilla's mock helper supplies an unbound connection; pinned Forge login filtering dereferences its null channel before observer admission or craft assembly. No motion/transfer assertion reached.34 input hashes unchanged; failed world, compiled classes, logs and bytecode/source diagnosis retained. Eight bootstrap physics-frame-queue warnings remain unqualified. Exact all-process/global-lock clear20:28:57UTC, yielded to Condenser. No auto retry.

- Second dynamic qualification `50d2268c-1e42-4a0c-8e1a-c0cda4aae759`: actual bound-observer/test compilation and **24/24 Gradle JVM checks PASS**; exactly one native test FAILED before assembly, EXIT1/46s. Null channel resolved, but Forge's `ConnectionType.forVersionFlag` received null version metadata. Full actual stack/37 unchanged inputs/compiled classes/failed world retained;11 bootstrap queue warnings unqualified. Exact process/flock clear21:18:19UTC, yielded to Water Safety. No physics/transfer result or auto retry.

- Third dynamic qualification `b0098bf8-ddfd-45d5-b59b-285588dfe7ae`: public-Forge-intention/active-observer probe actually recompiled; **24/24 fresh Gradle JVM tests PASS**. Observer admitted, assembly/loaded-registry guards and Overworld Y384 gravity control succeeded. **Exactly1 native test FAILED at first space coast assertion tick160**: x0 unchanged since140 despite vx10, expected dx10. EXIT1/48s,39 inputs unchanged;16 missing-ship frames/9 bootstrap queue warnings retained, full logs/classes/XML/failed world/AUDIT/DIAGNOSIS preserved. All owned Java absent/global flock free21:57:30.488025UTC; no auto retry. Loaded registry does not independently prove a destination native body; game-tick/native-clock alignment and root cause remain unresolved. No return hop/full coast/client/passenger/restart acceptance.

- Paced dynamic qualification `6e823869-44f8-4377-a7d8-e2047f39f6ae`: clock/event code compiled,33 fresh Gradle JVM tests PASS/42 stable inputs. Actual20 coast ticks spanned1.003366s but dx0/vx10 still failed expected10; gravity40ticks2.003877s positive control passed. EXIT1/129.27s/no timeout,17 missing-ship frames/10 bootstrap queues retained. All owned Java absent/project lock free22:27:08.771217UTC. Pacing alone insufficient; destination registration/projection and real-client loading remain unresolved.

## Native server sub-gates

```sh
# Default: original static round trip (previously qualified; method remains unchanged).
./scripts/gradle-lab.sh runGameTestServer -PattemptId=YOUR_NEW_UNIQUE_ID
# Separate loaded/dynamic test — admission fixed; first coast assertion failed:
./scripts/gradle-lab.sh test runGameTestServer -PprobeGate=dynamic -PattemptId=ANOTHER_NEW_UNIQUE_ID
```

Each command is a deliberate bounded invocation with frozen inputs, fresh owned fixture and the VS project lock; no automatic retry. No home-wide grant queue applies to independent functional tests. `probeGate` accepts only `static` or `dynamic`; exactly one class/test registers. The positive log must include its corresponding static/dynamic scope marker and one required authored test passed. No unknown-gate fallback is allowed.

The corrected dynamic fixture uses normal `PlayerList.placeNewPlayer` with a bound synthetic Netty connection solely to exercise player-driven native ship loading. The vanilla helper's unbound connection failed pinned Forge login filtering; it is no longer used. The probe-only transport discards outbound traffic and cannot prove packet delivery or real-client negotiation. The subsequently compiled/exercised public-intention correction uses Forge's public server-intention hook to initialize its declared version and checks active transport/listener/registry identity; it does not mutate private channel attributes. It requires loaded-registry membership through both transfers, an unanchored Y384 Overworld gravity positive control, nine independent space-coast snapshots (finite actual motion, near-constant velocity and bounded vertical/lateral drift), exact ship/cargo state and observer removal. It does not clamp velocities after the initial conditions. This synthetic actor is **not** proof of seats, real clients, rendered tracking or restart.

Historical `../../generated/evidence/observer-transport-correction-fc0bcf58-f187-42d1-8adc-5788babb1a32/` passed24 standalone JVM cases, including four actual Netty transport cases; the wrapper compiled in `50d2268c...` but failed Forge version filtering. The public-intention checkpoint `../../generated/evidence/forge-intention-fixture-ready-dee5b879-91a2-4ac4-94db-45b65119f8c1/` likewise passed24 standalone cases before framework compilation. Latest `b0098bf8...` actually recompiled that MC correction, executed24 fresh Gradle JVM cases successfully and passed observer admission, but failed the native coast-motion oracle. Original static assertions and dynamic motion tolerances/ticks/deadline remain unchanged. Earlier admission and missing-JOML classpath failures are retained, not relabeled passes. No retry is queued; another invocation needs diagnosis, stable inputs, deliberate bounded execution under the scoped project lock and a new UUID world. Coordinate genuine external conflicts or actual benchmark quiet windows, not a blanket Java/native queue. See `../../COMPLETENESS.md` for the acceptance matrix.

New clock candidate `54836043-f55f-4914-9f4c-a96c82eb0743` adds probe-only owner/GameTest-scoped20Hz monotonic START-event pacing, actual clock/cadence logs and9 deterministic pacing tests.33 standalone JVM cases and8 source guards PASS; event wiring was uncompiled at that checkpoint, then compiled/exercised in the failed paced qualification `6e823869...`. Additive loaded/canonical-view logging subsequently compiled/executed in `fc29a5b7...`:33 fresh JVM tests PASS/42 stable inputs, coast still FAILED over1.000723s. All4 samples agreed/shared transform/inertia references; switching authority is not justified.18 missing-ship frames/9bootstrap queues retained. Direct destination body/voxel liveness remains unobserved; exact project clearance22:40:25.488966UTC, no retry queued. Static source, motion oracle, sample ticks and360-tick deadline remain exact. See checkpoint `POLICY-AND-CLOCK.md` for root ACK, resource-reporting correction and safe project-lock migration; historical coast failure retained.

Every invocation defaults to a new UUID fixture under `../../instances/verification/<id>/native`. Existing worlds are refused. No EULA is accepted by any source task. Inspect actual exit/log/test summary; command launch or timeout is not a pass.

One authored native test checks static ship assembly, the exact 12 hull/block states, all three block entities including sentinel cargo/fuel, dimension ownership, cleared source chunks, round trip, unequal section-layout rejection, static Y384 and diagnostic record serialization. It deliberately does not claim a seated player, dynamic native physics, client tracking or a real process restart.

## Manual client/server transfer probe

The `runClient`/`runServer` development configurations include the separate `better_spaceflight_probe` mod. They are NOT the final human-playable modpack or production launchers.

After obtaining applicable EULA acceptance and a coordinated runtime slot, start a fresh server and real client in new per-run directories:

```sh
./scripts/gradle-lab.sh runServer -PattemptId=NEW_SERVER_ID
./scripts/gradle-lab.sh runClient -PattemptId=NEW_CLIENT_ID
```

These are separate invocations, not simultaneous heavyweight Gradle sessions. For the initial human transfer inspection, use `runClient` alone and create a new integrated-server world manually. After stopping it normally, an intentional restart uses the SAME attempt ID with `-PprobeRestart=true`; the task requires its own matching instance marker. Native GameTests never permit reuse. The final dedicated/client launch procedure must decouple a prepared server/client from Gradle before a paired session; do not bypass the shared lock or open a second heavy run to improvise this. A first dedicated launch may stop requesting EULA acceptance; the script will never accept on your behalf.

In a new test world as operator, with clear air 8 blocks above/east/south:

- `/spaceflight_probe begin`: creates a static gold-controller/copper-tank/iron-hull/chest fixture and seats the player. Stores its exact state and marker in Overworld SavedData.
- `/spaceflight_probe hop`: requires that fixture's seat; moves the native ship to/from the empty space dimension, checks cargo/fuel/source clearance, teleports the player and remounts a native VS seat.
- `/spaceflight_probe status`: checks recorded dimension, identity, hull and block-entity NBT. Does not prove client tracking/rendering or restart merely by printing a message.
- `/spaceflight_probe remount`: explicit operator recovery after relog, only if not already mounted.

If a command fails, preserve the world and full log and inspect the partial state; do not rerun begin, reconstruct a craft, or silently label recovery successful. This probe intentionally does not implement the later crash-transactional coordinator.

Required manual observations still include actual hull/cargo rendering after both handoffs, mounting/camera/interaction correctness, reconnect/restart with exactly one craft and unchanged sentinel inventories, active native physics at altitude, and zero ship gravity/drag in space. The probe presently holds craft static for safe transfer inspection and does not establish these dynamic properties.

## Source boundary

`Vs2411Adapter` contains all current VS internal calls; unsupported versions fail at mod construction. `AssemblyTranslation` uses immutable geometric-center snapshots and exact integral offsets, never COM-world positions. `SectionLayout` guards against index-based chunk copying across incompatible vertical layouts. The native adapter operation is explicitly probe-only, destructive, game-thread-only and not a production transaction.

`src/probe` is not part of the runtime JAR. Do not migrate its operator shortcuts, sentinel items or static anchoring into survival gameplay. Source build provenance identifies uncommitted inputs with a runtime source fingerprint rather than inventing a Git revision.

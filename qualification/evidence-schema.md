# Batch 3: exact assertion/tier mapping and NOT_EXECUTABLE evidence validator

**Review-only, not production qualification.** `qualification/mandatory-scenarios.json` is the machine roster (22 native + 10 fault + 5 client). Pure tests compare its exact IDs to `QualificationScenarios.java`. Only the **separate CLI** `scripts/qualification-evidence.py` inspects a human-authored JSON document; it does not load Forge/Minecraft, register GameTests, read world state or invoke any native/test/fault/client operation. It has an unconditional `NOT_EXECUTABLE:no_trustworthy_native_evidence_producer` guard: even a structurally complete document returns `status="blocked"`, `document_complete=true`, `execution_authenticated=false`, exit 2. It has **no pass/fail promotion branch**. A malformed/forged document returns `status="blocked"`, `document_complete=false` and actionable `issues`. Treat `document_complete` only as schema coverage, never an executed pass. `not_run` is reserved for a future trustworthy readiness verifier; without that producer it also blocks.

## Exact current assertion → tier → future independent proof

| Assertion ID | Tier | What an authenticated future producer must observe, never infer from a digest |
| --- | --- | --- |
| `native_original_artifacts` | native | Original VS/classloader/FN/FU byte hashes and transformed owner in live process. |
| `native_default_off` | native | Hooks remain OFF on ordinary server start; no travel admission. |
| `native_startup_checksum` | native | Rejected altered/missing artifact and corrupt journal on separate cold start. |
| `native_connected_component` | native | ≥2 occupied connected original ship IDs and physics component preserved. |
| `native_joint_identity` | native | Every native/logical original joint ID/endpoints persisted before/after. |
| `native_physics_liveness` | native | Physics-thread original body handles/ownership/listener callbacks live after handoff. |
| `native_pose_velocity` | native | Pose/inertia/linear+angular velocity/static flag equality at owner boundary. |
| `native_frame_boundary` | native | Single intended frame nonce/epoch removal+restore; no premature release. |
| `native_loaded_coverage` | native | Real FULL/pending-unload shipyard holder enumeration, not merely active chunks. |
| `native_unloaded_claims` | native | On-disk meaningful claims and ticks included or refused before mutation. |
| `native_block_entity_cargo` | native | Raw block/BE/cargo/fuel one-copy source/target bytes. |
| `native_capability_ticks` | native | Arbitrary ForgeCaps and tick identity/deadline/order from actual providers. |
| `native_actor_closure` | native | Online/offline/on-deck UUID/contact/riding/seat/player closure. |
| `native_destination_empty` | native | Loaded+unloaded target occupancy from both-world inventories, with durable backup. |
| `native_motion_gravity` | native | Time-aligned moving native body/displacement and production gravity at altitude. |
| `native_equal_layout_refusal` | native | Unequal layout refused with unchanged **both** worlds; not Twilight route transit. |
| `native_ascent_space` | native | Pilot Y512 nonce, exact surface allowlist → space arrival; full moving graph/actors/cargo. |
| `native_overworld_route` | native | Space → `minecraft:overworld`, actual landing/return with full graph unchanged. |
| `native_wastelands_route` | native | Space → `fallout_wastelands_:wastelands`, actual landing/return with full graph unchanged. |
| `native_abyssal_route` | native | Space → `better_abyssal_city:abyssal`, actual landing/return with full graph unchanged. |
| `native_twilight_route` | native | Space → `twilightforest:twilight_forest`, **actual unequal-layout transit/landing**, currently blocked. |
| `native_addon_veto` | native | Powered Eureka/Clockwork/Trackwork correctly veto without world mutation. |
| `fault_before_mutation` | fault | No-destructive-call gate ledger + identical source/target snapshots. |
| `fault_preparing_ambiguous` | fault | Fsync ambiguity retains claims, verified physics stop and cold-start seal. |
| `fault_cancel_no_mutation` | fault | Genuine zero-dispatch schema-2 terminal fsync; no late re-freeze. |
| `fault_destination_copy` | fault | Target-copy fault retained world and full both-world restore bytes. |
| `fault_source_clear` | fault | Mid source-clear fault, no mixed commit; full recovery bytes. |
| `fault_partial_component` | fault | Between member/frame fault, all bodies/joints quarantined and same IDs. |
| `fault_joint_restore` | fault | Native same-ID collision/throw; no partial release or duplicate joint. |
| `fault_release_fsync` | fault | Atomic release/fsync ordering and irrevocable ACK across cold restart. |
| `fault_restart_reconcile` | fault | Killed process, retained exact failed world and separate cold owner startup refusal/terminality. |
| `fault_restore_integrity` | fault | Independent complete source+destination/actors/cargo restore/backup hashes. |
| `client_four_markers` | client | Two independent real clients, zero before grant/four after real grant/revoke; packet proof. |
| `client_pilot_confirmation` | client | Controller-only live nonce, two real users and server authority recheck. |
| `client_mounted_tracking` | client | Two simultaneous views + server original IDs/seat/camera on moving crossing. |
| `client_concurrent_users` | client | Disconnect/swap/reconnect without offer/craft/cargo/player duplication. |
| `client_restart_persistence` | client | Same frozen world after cold JVM restart, one craft/joints/players/cargo/gate. |

All five route entries are separate mandatory outcomes; refusal `native_equal_layout_refusal` cannot stand in for `native_twilight_route`. The fault points and exact before/after fixtures are in [`fault-restart-fixtures.md`](fault-restart-fixtures.md); real two-client procedure is in [`two-real-client-procedure.md`](two-real-client-procedure.md).

## Authored blocked document and validation interface

Top-level **exact fields**: `schema=1`, `origin="authored_blocked"`, `executable=false`, `candidate_sha256`, `historical_probe_sha256`, `original_vs_sha256`, `forge_expected_sha256`, `tiers`, `scenarios`. Candidate is the separately reviewed path+byte hash of **all** effective authored sources/config/verification metadata (see batch2), historical `src/probe` is separately pinned and never qualifying. Original VS and mapped Forge expected hashes are exact constants from batch2; reporting them in JSON does **not** prove bytes loaded or Forge success.

`tiers` has exactly `native`, `fault`, `client`. Each record has exactly: `attempt_id`, `world_id` (both null in an authored blocked document), `execution="not_executable"`, `runtime_log_sha256=null`, `process_exit=null`, `cleanup="not_started"`, `client_proof=null`. In any future run, each attempt/world UUID4 must be unique across all tiers and cannot point at a shared/friends' save; runtime logs, observed exit, cleanup, original failed-world retention and two independent client proof must be independently authenticated. Non-null authored claims currently generate blockers, not acceptance.

`scenarios` contains **exactly 37** unique objects with exactly: `id`, `tier`, `candidate_sha256`, `status="blocked"`, nonempty `reason`, `evidence=null`, `fault_point=null`, `route`. Each five-route row uses its exact source/destination list from the roster; other rows have `route=null`. A fault-point claim requires the exact token plus actual pre/post snapshots and retained world, yet still blocks absent an authentic producer. `passed`, `failed` or `not_run` self-reporting, fabricated static-probe/Netty evidence, repeated world/attempt UUIDs, mixed candidate/probe pin, missing or alias ID, fake client proof and ambiguous faults are **refused**. No filename, digest, all-pass JSON, screenshot hash or `"clients":2` can prove execution.

**Read-only validator invocation template** (schema inspection only; does NOT execute qualification):

```sh
python3 -B scripts/qualification-evidence.py \
  --document PATH_TO_AUTHORED_BLOCKED_JSON \
  --expected-candidate-sha256 REVIEWED_CURRENT_CANDIDATE_PATH_BYTES_SHA256 \
  --expected-historical-probe-sha256 REVIEWED_SEPARATE_SRC_PROBE_PIN
```

The CLI compares both reviewed pins against **current** repo path+byte digests and every row, reads JSON ≤1 MiB with duplicate-key/NaN/symlink refusal, prints one machine-readable blocked receipt and exits 2. A user-supplied matching hash still cannot authenticate runtime logs or server/client/physics truth. No CLI `--ready`, forged attestation or backdoor exists. Any future executed-evidence validator requires a separately reviewed trustworthy producer and human runtime approval; until then `NOT_EXECUTABLE` is mandatory.

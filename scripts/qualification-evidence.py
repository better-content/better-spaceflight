#!/usr/bin/env python3
"""Author-only schema checker. NOT_EXECUTABLE: no trusted evidence producer exists.

This program never promotes a result to passed/failed/not_run; a syntactically
complete authored BLOCKED document is still BLOCKED. It does not launch tests.
"""
import importlib.util
import json
from pathlib import Path
import re
import sys
import uuid

ROOT = Path(__file__).resolve().parent.parent
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
TIERS = ("native", "fault", "client")
STATUS = ("passed", "failed", "blocked", "not_run")
# Independent frozen IDs: the JSON roster may map assertions to tiers but may
# NOT silently replace a mandatory assertion with another same-prefix ID.
REQUIRED_IDS = {
    "native": (
        "native_original_artifacts", "native_default_off", "native_startup_checksum",
        "native_connected_component", "native_joint_identity", "native_physics_liveness",
        "native_pose_velocity", "native_frame_boundary", "native_loaded_coverage",
        "native_unloaded_claims", "native_block_entity_cargo", "native_capability_ticks",
        "native_actor_closure", "native_destination_empty", "native_motion_gravity",
        "native_equal_layout_refusal", "native_ascent_space", "native_overworld_route",
        "native_wastelands_route", "native_abyssal_route", "native_twilight_route",
        "native_addon_veto"),
    "fault": (
        "fault_before_mutation", "fault_preparing_ambiguous", "fault_cancel_no_mutation",
        "fault_destination_copy", "fault_source_clear", "fault_partial_component",
        "fault_joint_restore", "fault_release_fsync", "fault_restart_reconcile",
        "fault_restore_integrity"),
    "client": (
        "client_four_markers", "client_pilot_confirmation", "client_mounted_tracking",
        "client_concurrent_users", "client_restart_persistence")
}
ROUTES = {
    "native_ascent_space": ("surface-allowlist", "better_spaceflight:space"),
    "native_overworld_route": ("better_spaceflight:space", "minecraft:overworld"),
    "native_wastelands_route": ("better_spaceflight:space", "fallout_wastelands_:wastelands"),
    "native_abyssal_route": ("better_spaceflight:space", "better_abyssal_city:abyssal"),
    "native_twilight_route": ("better_spaceflight:space", "twilightforest:twilight_forest"),
}
FAULT_POINTS = {
    "fault_before_mutation": "before_game_mutation",
    "fault_preparing_ambiguous": "preparing_fsync_uncertain",
    "fault_cancel_no_mutation": "zero_dispatch_cancel",
    "fault_destination_copy": "target_copy_midway",
    "fault_source_clear": "source_clear_midway",
    "fault_partial_component": "between_component_members",
    "fault_joint_restore": "native_same_id_joint_restore",
    "fault_release_fsync": "release_atomic_replace",
    "fault_restart_reconcile": "cold_process_restart",
    "fault_restore_integrity": "source_and_destination_restore",
}


def unique_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate_json_key: " + key)
        value[key] = item
    return value


def read_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError("unsafe_or_oversized_json")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs,
                      parse_constant=lambda name: (_ for _ in ()).throw(ValueError("nonfinite_json_number")))


def is_hash(value):
    return isinstance(value, str) and HEX64.fullmatch(value) is not None


def canonical_uuid4(value):
    if not isinstance(value, str):
        return False
    try:
        parsed = uuid.UUID(value)
        return parsed.version == 4 and str(parsed) == value
    except ValueError:
        return False


def roster(root):
    document = read_json(root / "qualification/mandatory-scenarios.json")
    if not isinstance(document, dict) or set(document) != {"schema", *TIERS, "routes"} or document["schema"] != 1:
        raise ValueError("invalid_roster_schema")
    found = []
    for tier in TIERS:
        ids = document[tier]
        if not isinstance(ids, list) or tuple(ids) != REQUIRED_IDS[tier]:
            raise ValueError("mandatory_roster_id_mismatch: " + tier)
        found.extend(ids)
    if len(set(found)) != 37 or document["routes"] != {key: list(value) for key, value in ROUTES.items()}:
        raise ValueError("invalid_roster_routes_or_duplicates")
    return {id_: tier for tier in TIERS for id_ in document[tier]}


def validate(document, root, expected_candidate, expected_probe, observed_candidate, observed_probe):
    """No evidence authenticator exists. Successful schema inspection is NOT a test pass."""
    issues = []
    outcome = {"schema": 1, "status": "blocked", "document_complete": False,
               "execution_authenticated": False, "issues": issues,
               "blocking_reasons": ["NOT_EXECUTABLE:no_trustworthy_native_evidence_producer"],
               "scenario_count": 0}
    try:
        expected = roster(root)
    except (OSError, ValueError, TypeError, KeyError) as error:
        issues.append("invalid_mandatory_roster:" + str(error))
        return outcome
    if not isinstance(document, dict) or document.get("schema") != 1:
        issues.append("invalid_document_schema")
        return outcome
    if set(document) != {"schema", "origin", "executable", "candidate_sha256",
                         "historical_probe_sha256", "original_vs_sha256", "forge_expected_sha256",
                         "tiers", "scenarios"}:
        issues.append("unexpected_or_missing_document_fields")
    if not is_hash(expected_candidate) or not is_hash(expected_probe):
        issues.append("unreviewed_candidate_or_probe_pin")
    if expected_candidate != observed_candidate or expected_probe != observed_probe:
        issues.append("frozen_candidate_or_historical_probe_drift")
    if document.get("candidate_sha256") != expected_candidate or document.get("historical_probe_sha256") != expected_probe:
        issues.append("mixed_or_missing_candidate_probe_hash")
    if document.get("origin") != "authored_blocked":
        issues.append("historical_probe_or_untrusted_origin")
    if document.get("executable") is not False:
        issues.append("executable_claim_without_producer")
    if document.get("original_vs_sha256") != "f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53":
        issues.append("original_vs_pin_missing_or_mismatched")
    if document.get("forge_expected_sha256") != "57930057043b9da110644d454c8efc3a247cb213bb310a1e2a46dbac2743f194":
        issues.append("forge_expected_pin_missing_or_mismatched")
    tiers = document.get("tiers")
    tier_ids = {}
    if not isinstance(tiers, dict) or set(tiers) != set(TIERS):
        issues.append("missing_or_extra_tier")
        tiers = {}
    for tier in TIERS:
        record = tiers.get(tier)
        if not isinstance(record, dict):
            issues.append("invalid_tier_record:" + tier)
            continue
        if set(record) != {"attempt_id", "world_id", "execution", "runtime_log_sha256",
                           "process_exit", "cleanup", "client_proof"}:
            issues.append("unexpected_or_missing_tier_fields:" + tier)
        attempt = record.get("attempt_id")
        world = record.get("world_id")
        for kind, value in (("attempt_id", attempt), ("world_id", world)):
            if value is not None:
                if not canonical_uuid4(value):
                    issues.append("invalid_" + kind + ":" + tier)
                elif (kind, value) in tier_ids or any(id_ == value for _, id_ in tier_ids):
                    issues.append("reused_world_or_attempt_uuid:" + tier)
                tier_ids[(kind, value)] = tier
        if (attempt is not None or world is not None or record.get("execution") != "not_executable"
            or record.get("runtime_log_sha256") is not None or record.get("process_exit") is not None
            or record.get("cleanup") != "not_started" or record.get("client_proof") is not None):
            issues.append("runtime_or_client_claim_without_producer:" + tier)
        if record.get("client_proof") is not None:
            issues.append("unauthenticated_two_real_client_proof:" + tier)
            if isinstance(record["client_proof"], dict) and record["client_proof"].get("kind") == "synthetic_netty":
                issues.append("fake_client_evidence:" + tier)
        if record.get("runtime_log_sha256") is not None and not is_hash(record["runtime_log_sha256"]):
            issues.append("invalid_runtime_log_hash:" + tier)
    rows = document.get("scenarios")
    if not isinstance(rows, list):
        issues.append("missing_scenario_rows")
        return outcome
    outcome["scenario_count"] = len(rows)
    seen = set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            issues.append("invalid_scenario_row:" + str(index))
            continue
        if set(row) != {"id", "tier", "candidate_sha256", "status", "reason",
                        "evidence", "fault_point", "route"}:
            issues.append("unexpected_or_missing_scenario_fields:" + str(index))
        name = row.get("id")
        if not isinstance(name, str) or name not in expected or name in seen:
            issues.append("unknown_or_duplicate_scenario:" + str(name))
            continue
        seen.add(name)
        if row.get("tier") != expected[name]:
            issues.append("scenario_tier_mismatch:" + name)
        expected_route = list(ROUTES[name]) if name in ROUTES else None
        if row.get("route") != expected_route:
            issues.append("route_alias_or_missing_route:" + name)
        if row.get("candidate_sha256") != expected_candidate:
            issues.append("mixed_scenario_candidate:" + name)
        state = row.get("status")
        if state not in STATUS:
            issues.append("invalid_status:" + name)
        elif state != "blocked":
            issues.append("unauthenticated_executed_status:" + name)
        if not isinstance(row.get("reason"), str) or not row["reason"].strip():
            issues.append("missing_blocker_reason:" + name)
        evidence = row.get("evidence")
        if evidence is not None:
            issues.append("unverified_runtime_or_synthetic_evidence:" + name)
            if isinstance(evidence, dict) and evidence.get("kind") in ("historical_static_probe", "synthetic_netty"):
                issues.append("historical_probe_or_fake_client_evidence:" + name)
        if name.startswith("fault_"):
            point = row.get("fault_point")
            if point is None and state != "blocked":
                issues.append("ambiguous_fault_injection:" + name)
            if point is not None:
                if point != FAULT_POINTS[name] or not isinstance(evidence, dict) or not all(
                    evidence.get(field) for field in ("before_snapshot", "after_snapshot", "retained_world")):
                    issues.append("ambiguous_fault_injection:" + name)
                else:
                    issues.append("unauthenticated_fault_injection:" + name)
        elif row.get("fault_point") is not None:
            issues.append("misassigned_fault_point:" + name)
    if seen != set(expected):
        issues.append("missing_mandatory_scenarios:" + ",".join(sorted(set(expected) - seen)))
    # No status other than blocked can leave this function, even with 37 well-formed rows.
    outcome["document_complete"] = not issues
    return outcome


def args(argv):
    flags = {"--document", "--expected-candidate-sha256", "--expected-historical-probe-sha256"}
    if len(argv) != 6 or set(argv[::2]) != flags:
        raise ValueError("usage: --document JSON --expected-candidate-sha256 HEX64 "
                         "--expected-historical-probe-sha256 HEX64")
    return dict(zip(argv[::2], argv[1::2]))


def main(argv):
    try:
        options = args(argv)
        spec = importlib.util.spec_from_file_location("qualification_preflight", ROOT / "scripts/qualification-native-preflight.py")
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        document = read_json(Path(options["--document"]))
        outcome = validate(document, ROOT, options["--expected-candidate-sha256"],
                           options["--expected-historical-probe-sha256"],
                           gate.candidate_digest(ROOT), gate.historical_probe_digest(ROOT))
    except (OSError, ValueError, TypeError, KeyError) as error:
        outcome = {"schema": 1, "status": "blocked", "document_complete": False,
                   "execution_authenticated": False, "issues": ["input_or_read_error:" + str(error)],
                   "blocking_reasons": ["NOT_EXECUTABLE:no_trustworthy_native_evidence_producer"],
                   "scenario_count": 0}
    print(json.dumps(outcome, sort_keys=True, separators=(",", ":")), flush=True)
    return 2  # Never an executed test failure, success or production qualification.


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

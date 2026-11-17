"""Pure forged/blocked evidence documents; no native process, client or world."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import re
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("qualification_evidence", ROOT / "scripts/qualification-evidence.py")
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)
CANDIDATE = "a" * 64
PROBE = "b" * 64


class EvidenceValidatorTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".tmp/java").mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / ".tmp/java")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "qualification").mkdir()
        (self.root / "qualification/mandatory-scenarios.json").write_bytes(
            (ROOT / "qualification/mandatory-scenarios.json").read_bytes())
        self.ids = evidence.roster(self.root)
        self.document = {
            "schema": 1, "origin": "authored_blocked", "executable": False,
            "candidate_sha256": CANDIDATE, "historical_probe_sha256": PROBE,
            "original_vs_sha256": "f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53",
            "forge_expected_sha256": "57930057043b9da110644d454c8efc3a247cb213bb310a1e2a46dbac2743f194",
            "tiers": {tier: {"attempt_id": None, "world_id": None, "execution": "not_executable",
                             "runtime_log_sha256": None, "process_exit": None, "cleanup": "not_started",
                             "client_proof": None} for tier in evidence.TIERS},
            "scenarios": [{"id": id_, "tier": tier, "candidate_sha256": CANDIDATE,
                           "status": "blocked", "reason": "No trusted production dispatch or backup",
                           "evidence": None, "fault_point": None,
                           "route": list(evidence.ROUTES[id_]) if id_ in evidence.ROUTES else None}
                          for id_, tier in self.ids.items()]}

    def check(self, document=None):
        return evidence.validate(self.document if document is None else document, self.root,
                                 CANDIDATE, PROBE, CANDIDATE, PROBE)

    def row(self, id_):
        return next(item for item in self.document["scenarios"] if item["id"] == id_)

    def test_valid_authored_blocked_document_is_complete_but_never_qualified(self):
        result = self.check()
        self.assertEqual((37, True, False, "blocked", []),
                         (result["scenario_count"], result["document_complete"],
                          result["execution_authenticated"], result["status"], result["issues"]))
        self.assertIn("NOT_EXECUTABLE", result["blocking_reasons"][0])
        java = (ROOT / "src/test/java/com/bettercontent/spaceflight/qualification/QualificationScenarios.java").read_text()
        ids = re.findall(r'new Scenario\("([a-z0-9_]+)", Tier\.(NATIVE|FAULT|CLIENT)', java)
        self.assertEqual(self.ids, {name: tier.lower() for name, tier in ids})
        self.assertEqual({name: tier for tier, names in evidence.REQUIRED_IDS.items() for name in names}, self.ids)

    def test_every_mandatory_assertion_has_tier_documentation_and_exact_procedure(self):
        matrix = (ROOT / "qualification/evidence-schema.md").read_text()
        faults = (ROOT / "qualification/fault-restart-fixtures.md").read_text()
        clients = (ROOT / "qualification/two-real-client-procedure.md").read_text()
        for id_, tier in self.ids.items():
            self.assertIn("| `" + id_ + "` | " + tier + " |", matrix)
            if tier == "fault":
                self.assertIn("| `" + id_ + "` |", faults)
            elif tier == "client":
                self.assertIn("| `" + id_ + "` |", clients)
        for id_ in evidence.ROUTES:
            self.assertIn("| `" + id_ + "` |", clients)
        self.assertIn("native_equal_layout_refusal", clients)
        self.assertIn("native_twilight_route", clients)

    def test_roster_same_count_replacement_cannot_remove_route_fault_or_client(self):
        path = self.root / "qualification/mandatory-scenarios.json"
        original = json.loads(path.read_text())
        for tier, mandatory in (("native", "native_twilight_route"),
                                ("fault", "fault_restore_integrity"),
                                ("client", "client_restart_persistence")):
            modified = copy.deepcopy(original)
            modified[tier][modified[tier].index(mandatory)] = tier + "_fake_replacement"
            path.write_text(json.dumps(modified))
            result = self.check()
            self.assertEqual("blocked", result["status"])
            self.assertFalse(result["document_complete"])
            self.assertTrue(any("mandatory_roster_id_mismatch: " + tier in issue for issue in result["issues"]))
        path.write_text(json.dumps(original))

    def test_forged_all_pass_and_historical_probe_or_synthetic_netty_never_promote(self):
        for row in self.document["scenarios"]:
            row["status"] = "passed"
            row["evidence"] = {"kind": "historical_static_probe", "sha256": "c" * 64}
        self.document["origin"] = "historical_static_probe"
        result = self.check()
        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["document_complete"])
        self.assertTrue(any("unauthenticated_executed_status" in issue for issue in result["issues"]))
        self.assertTrue(any("historical_probe_or_fake_client_evidence" in issue for issue in result["issues"]))
        self.assertIn("historical_probe_or_untrusted_origin", result["issues"])
        self.row("client_mounted_tracking")["evidence"] = {"kind": "synthetic_netty"}
        self.assertTrue(any("fake_client_evidence" in issue for issue in self.check()["issues"]))

    def test_mixed_candidate_missing_scenario_and_route_alias(self):
        self.row("native_twilight_route")["route"] = ["better_spaceflight:space", "twilightforest:Twilight_Forest"]
        self.row("native_ascent_space")["candidate_sha256"] = "f" * 64
        self.document["scenarios"] = [row for row in self.document["scenarios"]
                                      if row["id"] != "native_joint_identity"]
        result = self.check()
        self.assertFalse(result["document_complete"])
        self.assertTrue(any("mixed_scenario_candidate" in issue for issue in result["issues"]))
        self.assertTrue(any("route_alias_or_missing_route" in issue for issue in result["issues"]))
        self.assertTrue(any("missing_mandatory_scenarios" in issue for issue in result["issues"]))
        self.assertIn("frozen_candidate_or_historical_probe_drift",
                      evidence.validate(self.document, self.root, CANDIDATE, PROBE, "f" * 64, PROBE)["issues"])

    def test_reused_attempt_world_uuid_and_unverified_runtime_exit_are_blocked(self):
        reused = str(uuid.uuid4())
        for tier in ("native", "fault"):
            self.document["tiers"][tier]["attempt_id"] = reused
            self.document["tiers"][tier]["world_id"] = reused
        self.document["tiers"]["native"].update(execution="claimed_run", runtime_log_sha256="d" * 64,
                                                  process_exit=0, cleanup="claimed_clean")
        result = self.check()
        self.assertFalse(result["document_complete"])
        self.assertTrue(any("reused_world_or_attempt_uuid" in issue for issue in result["issues"]))
        self.assertTrue(any("runtime_or_client_claim_without_producer" in issue for issue in result["issues"]))

    def test_fake_client_proof_and_ambiguous_fault_injection_are_blocked(self):
        self.document["tiers"]["client"]["client_proof"] = {"kind": "synthetic_netty", "clients": 2}
        fault = self.row("fault_source_clear")
        fault["status"] = "passed"
        fault["fault_point"] = "unknown_after_source_clear"
        fault["evidence"] = {"before_snapshot": "digest", "after_snapshot": "digest"}
        result = self.check()
        self.assertIn("fake_client_evidence:client", result["issues"])
        self.assertIn("ambiguous_fault_injection:fault_source_clear", result["issues"])
        self.assertEqual("blocked", result["status"])

    def test_read_only_cli_accepts_schema_but_blocks_actual_execution(self):
        gate_spec = importlib.util.spec_from_file_location("preflight_for_pins", ROOT / "scripts/qualification-native-preflight.py")
        gate = importlib.util.module_from_spec(gate_spec)
        gate_spec.loader.exec_module(gate)
        candidate, probe = gate.candidate_digest(ROOT), gate.historical_probe_digest(ROOT)
        document = copy.deepcopy(self.document)
        document["candidate_sha256"], document["historical_probe_sha256"] = candidate, probe
        for row in document["scenarios"]:
            row["candidate_sha256"] = candidate
        path = self.root / "blocked.json"
        path.write_text(json.dumps(document))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(2, evidence.main(["--document", str(path),
                                               "--expected-candidate-sha256", candidate,
                                               "--expected-historical-probe-sha256", probe]))
        result = json.loads(output.getvalue())
        self.assertEqual(("blocked", True, False), (result["status"], result["document_complete"],
                                                   result["execution_authenticated"]))
        self.assertIn("NOT_EXECUTABLE", result["blocking_reasons"][0])

    def test_duplicate_json_fields_and_cli_bad_input_fail_closed(self):
        path = self.root / "duplicate.json"
        path.write_text('{"status":"blocked","status":"passed"}')
        with self.assertRaisesRegex(ValueError, "duplicate_json_key"):
            evidence.read_json(path)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(2, evidence.main([]))
        result = json.loads(output.getvalue())
        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["document_complete"])


if __name__ == "__main__":
    unittest.main()

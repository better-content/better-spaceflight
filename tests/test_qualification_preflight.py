"""Pure filesystem/receipt tests: never invoke Gradle, GameTests or Minecraft."""
import contextlib
import fcntl
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/qualification-native-preflight.py"
spec = importlib.util.spec_from_file_location("qualification_preflight", SCRIPT)
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class QualificationPreflightTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".tmp/java").mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / ".tmp/java")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "safe-repo"
        (self.repo / ".tmp").mkdir(parents=True)
        for dirname in ("src/main", "src/qualificationSupport", "src/test", "src/probe",
                        "tests", "qualification", "scripts", "gradle"):
            (self.repo / dirname).mkdir(parents=True)
        for filename in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                         "gradle.lockfile", "gradlew", "gradlew.bat",
                         "gradle/verification-metadata.xml", "gradle/wrapper/gradle-wrapper.jar",
                         "scripts/gradle-lab.sh", "scripts/qualification-native-preflight.py",
                         "qualification/coverage-matrix.md", "qualification/batch2-harness.md"):
            path = self.repo / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("frozen fixture\n")
        self.original = Path(self.temp.name) / "original-vs.jar"
        self.mapped = Path(self.temp.name) / "mapped-forge.jar"
        self.original.write_bytes(b"not the real original")
        self.mapped.write_bytes(b"not a trusted forge mapping")
        self.attempt = str(uuid.uuid4())
        self.args = ["--attempt-id", self.attempt, "--candidate-sha256", "0" * 64,
                     "--historical-probe-sha256", "0" * 64]

    def run_gate(self, args=None):
        return preflight.preflight(self.repo, self.args if args is None else args,
                                   original_vs=self.original, forge_mapped=self.mapped)

    def test_writes_exact_blocked_receipt_without_creating_test_or_world(self):
        result = self.run_gate()
        self.assertEqual("blocked", result["status"])
        self.assertEqual(self.attempt, result["attempt_id"])
        self.assertIn("production_dispatch_and_full_world_backup_unavailable", result["reason"])
        self.assertIn("frozen_candidate_mismatch", result["reason"])
        self.assertIn("original_vs_mismatch", result["reason"])
        self.assertIn("forge_verification_mismatch", result["reason"])
        self.assertIn("historical_probe_pin_mismatch", result["reason"])
        path = Path(result["receipt_path"])
        self.assertEqual(result, json.loads(path.read_text()))
        self.assertEqual(self.repo / ".tmp/qualification-receipts" / (self.attempt + ".json"), path)
        self.assertFalse((self.repo / "instances").exists())
        first = path.read_bytes()
        again = self.run_gate()
        self.assertEqual("blocked", again["status"])
        self.assertIn("existing_receipt_or_reused_uuid", again["reason"])
        self.assertEqual(first, path.read_bytes())

    def test_complete_candidate_and_separate_historical_probe_pin(self):
        candidate = preflight.candidate_digest(self.repo)
        historical = preflight.historical_probe_digest(self.repo)
        (self.repo / "src/test/older-existing-unit.java").write_text("previous test input changed")
        self.assertNotEqual(candidate, preflight.candidate_digest(self.repo))
        (self.repo / "src/test/older-existing-unit.java").unlink()
        (self.repo / "gradle/verification-metadata.xml").write_text("modified trust metadata")
        self.assertNotEqual(candidate, preflight.candidate_digest(self.repo))
        (self.repo / "gradle/verification-metadata.xml").write_text("frozen fixture\n")
        (self.repo / "src/probe/historical.java").write_text("separate historical probe")
        self.assertEqual(candidate, preflight.candidate_digest(self.repo))
        self.assertNotEqual(historical, preflight.historical_probe_digest(self.repo))

    def test_existing_attempt_or_symlink_escapes_are_refused_without_receipt(self):
        (self.repo / "instances/qualification" / self.attempt).mkdir(parents=True)
        result = self.run_gate()
        self.assertIn("existing_attempt_or_world", result["reason"][0])
        self.assertIsNone(result["receipt_path"])
        self.assertFalse((self.repo / ".tmp/qualification-receipts").exists())
        (self.repo / "instances/qualification" / self.attempt).rmdir()
        (self.repo / "instances/qualification").rmdir()
        (self.repo / "instances").rmdir()
        outside = Path(self.temp.name) / "untouched"
        outside.mkdir()
        (self.repo / "instances").symlink_to(outside, target_is_directory=True)
        result = self.run_gate()
        self.assertIn("unsafe_attempt_parent", result["reason"])
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse((self.repo / ".tmp/qualification-receipts").exists())

    def test_missing_original_artifact_blocks_without_download(self):
        self.original.unlink()
        result = self.run_gate()
        self.assertEqual("blocked", result["status"])
        self.assertIn("original_vs_missing", result["reason"])
        self.assertFalse(self.original.exists())
        self.assertFalse((self.repo / "instances").exists())

    def test_symlinked_receipt_directory_cannot_escape_project(self):
        outside = Path(self.temp.name) / "outside-receipts"
        outside.mkdir()
        (self.repo / ".tmp/qualification-receipts").symlink_to(outside, target_is_directory=True)
        result = self.run_gate()
        self.assertEqual("blocked", result["status"])
        self.assertIsNone(result["receipt_path"])
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse((self.repo / "instances").exists())

    def test_missing_arguments_emit_one_json_blocked_stdout_receipt(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(2, preflight.main([]))
        rows = output.getvalue().splitlines()
        self.assertEqual(1, len(rows))
        receipt = json.loads(rows[0])
        self.assertEqual("blocked", receipt["status"])
        self.assertIn("usage:", receipt["reason"][0])
        self.assertIsNone(receipt["receipt_path"])

    def test_lock_contended_or_alias_uuid_only_emit_blocked_stdout_data(self):
        lock = self.repo / ".tmp/spaceflight-runtime.lock"
        with lock.open("a+b") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_gate()
            self.assertIn("project_lock_contended", result["reason"])
            self.assertEqual(75, preflight.exit_code(result))
            self.assertIsNone(result["receipt_path"])
        alias = list(self.args)
        alias[1] = self.attempt.upper()
        result = self.run_gate(alias)
        self.assertIn("invalid_attempt_id", result["reason"][0])
        self.assertIsNone(result["receipt_path"])
        self.assertFalse((self.repo / "instances").exists())


if __name__ == "__main__":
    unittest.main()

"""Source/isolation guards only; these cannot qualify Minecraft/native motion."""
import hashlib
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[1]
PROBE = REPO / 'src/probe/java/com/bettercontent/spaceflight/probe'

class ProbeClockGuards(unittest.TestCase):
    def test_original_static_source_unchanged(self):
        self.assertEqual(hashlib.sha256((PROBE / 'TransferGameTests.java').read_bytes()).hexdigest(),
                         'd173a3d555bbe1ec6bc927578c93b01c9815bbf02fecdb76c93f2e55d972e1b7')

    def test_motion_oracle_unchanged(self):
        self.assertEqual(hashlib.sha256((PROBE / 'MotionChecks.java').read_bytes()).hexdigest(),
                         '6932f6d6517f2ca6e72ffff292c90b8b15390c60e21228ac7bc3d320dbfeabb8')

    def test_clock_is_explicit_gametest_owner_only(self):
        clock = (PROBE / 'ProbeClock.java').read_text()
        self.assertIn('instanceof GameTestServer', clock)
        self.assertIn('event.phase == TickEvent.Phase.START', clock)
        self.assertIn('event.getServer() == owner.getServer()', clock)
        self.assertIn('if (owner == observer)', clock)
        self.assertNotIn('setProbeState', clock)
        self.assertNotIn('setVelocity', clock)

    def test_launcher_lock_is_project_scoped(self):
        script = (REPO / 'scripts/gradle-lab.sh').read_text()
        self.assertIn('flock -n -E 75 "$lab/.tmp/spaceflight-runtime.lock"', script)
        self.assertNotIn('better-content-gradle.lock', script)
        self.assertIn('GRADLE_USER_HOME="$lab/generated/gradle-home"', script)

    def test_daemon_cpu_and_heap_explicit(self):
        properties = (REPO / 'gradle.properties').read_text()
        self.assertIn('-Xmx2G -XX:ActiveProcessorCount=2', properties)
        self.assertNotIn('-Xmx3G', properties)

if __name__ == '__main__':
    unittest.main()

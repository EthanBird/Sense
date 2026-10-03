import tempfile
import unittest
from pathlib import Path
from run_cold_loader import acceptance, validate_target


class ColdLoaderRunnerTest(unittest.TestCase):
    def test_target_rejects_hardware_old_evidence_and_path_injection(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'fresh'
            validate_target('emulator-5580', p)
            for serial, output in [('phone-123', p), ('emulator-5580', p.with_name('bad;cmd')), ('emulator-5580', Path(d))]:
                with self.assertRaises(ValueError): validate_target(serial, output)

    def test_instrumentation_success_alone_does_not_prove_loading_coverage(self):
        markers = 'thread=sense-decoder-loader\nsuspendPolicy=EVENT_THREAD\nownedMonitors=0\nmainSuspended=false'
        entry = {'loaderPausedMs': 6000, 'debuggerExitCode': 0}
        self.assertTrue(acceptance('OK (1 test)', entry, markers))
        self.assertFalse(acceptance('OK (1 test)', {}, markers))
        self.assertFalse(acceptance('OK (1 test)', entry, markers.replace('ownedMonitors=0', 'ownedMonitors=1')))
        self.assertFalse(acceptance('OK (1 test)', entry, markers.replace('mainSuspended=false', 'mainSuspended=true')))
        self.assertFalse(acceptance('FAILURES!!! OK (1 test)', entry, markers))
        self.assertFalse(acceptance('OK (1 test)', dict(entry, debuggerExitCode=1), markers))


if __name__ == '__main__': unittest.main()

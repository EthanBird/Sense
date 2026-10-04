import unittest

from test_input_configuration import assert_font_settled, assert_restored, density_state, restoration_order, settle_font_scale, validate_target


class DisposableConfigurationTargetTest(unittest.TestCase):
    def test_current_and_minimum_api_targets_are_explicit(self):
        validate_target("emulator-5580", "sense-input-quality", "37", "sense-input-quality")
        validate_target("emulator-5582", "sense-input-quality-api29", "29", "sense-input-quality-api29")
        validate_target("emulator-5584", "sense-input-quality-api29-google", "29", "sense-input-quality-api29-google")

    def test_physical_phone_and_unknown_names_are_rejected(self):
        for serial, actual, expected in [
            ("PHONE", "sense-input-quality", "sense-input-quality"),
            ("emulator-5582", "Personal_Phone", "Personal_Phone"),
            ("emulator-5582", "Personal_Phone", "sense-input-quality-api29"),
            ("emulator-5582", "sense-input-quality", "sense-input-quality-api29"),
        ]:
            with self.subTest(serial=serial, actual=actual, expected=expected), self.assertRaises(ValueError):
                validate_target(serial, actual, "29", expected)

    def test_wrong_api_is_not_counted_as_minimum_platform_coverage(self):
        with self.assertRaises(ValueError):
            validate_target("emulator-5582", "sense-input-quality-api29", "37", "sense-input-quality-api29")

    def test_density_without_override(self):
        self.assertEqual({"physical": 420, "override": None, "effective": 420}, density_state("Physical density: 420\n"))

    def test_density_preserves_override_separately(self):
        self.assertEqual({"physical": 420, "override": 480, "effective": 480}, density_state("Physical density: 420\nOverride density: 480\n"))

    def test_invalid_or_duplicate_density_fails(self):
        for text in ["", "Override density: 480", "Physical density: 0", "Physical density: 420\nPhysical density: 480", "Physical density: 420\nOverride density: 0", "Physical density: 420\nOverride density: 480\nOverride density: 560"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                density_state(text)

    def test_restore_requires_settings_and_density(self):
        settings = {"system/font_scale": "1.0"}
        density = density_state("Physical density: 420")
        assert_restored(settings, dict(settings), density, dict(density))
        with self.assertRaises(ValueError):
            assert_restored(settings, {}, density, density)
        with self.assertRaises(ValueError):
            assert_restored(settings, settings, density, density_state("Physical density: 420\nOverride density: 420"))

    def test_missing_font_scale_is_restored_after_rotation_writes(self):
        values = [(('system', 'font_scale'), 'null'), (('system', 'user_rotation'), '0'),
                  (('secure', 'default_input_method'), 'original')]
        self.assertEqual([values[1], values[2], values[0]], restoration_order(values))

    def test_async_default_write_is_observed_and_original_absence_is_restored(self):
        replies = iter(['1.0', 'null', 'null', 'null']); writes = []
        def adb(*args):
            if args[2] == 'get': return next(replies)
            writes.append(args)
            return ''
        attempts = settle_font_scale(adb, 'null', pause=lambda _: None)
        self.assertEqual([['1.0'], ['null', 'null', 'null']], attempts)
        self.assertEqual(2, len(writes))
        self.assertTrue(all(command[2] == 'delete' for command in writes))

    def test_font_restore_retries_are_bounded_and_failure_observations_preserved(self):
        writes = []
        def adb(*args):
            if args[2] == 'get': return '2.0'
            writes.append(args)
            return ''
        self.assertEqual([['2.0']] * 4, settle_font_scale(adb, '1.0', pause=lambda _: None))
        self.assertEqual(4, len(writes))
        self.assertTrue(all(command[2] == 'put' and command[-1] == '1.0' for command in writes))

    def test_later_matching_value_does_not_replace_three_stable_observations(self):
        assert_font_settled([['1.0'], ['null', 'null', 'null']], 'null')
        for attempts in [[], [['1.0']] * 4, [['null', 'null']], [['null'] * 3, ['1.0']]]:
            with self.subTest(attempts=attempts), self.assertRaises(ValueError):
                assert_font_settled(attempts, 'null')


if __name__ == "__main__":
    unittest.main()

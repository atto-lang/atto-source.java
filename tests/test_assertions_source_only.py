"""Lexical source guard only: it does NOT compile or execute Atto guest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ASSERT = ROOT / "stdlib/std/assert.atto"
CHECK = ROOT / "stdlib/std/test_assert.atto"
REGISTRY = ROOT / "src/main/java/dev/oreslang/stdlib/CoreLibrarySources.java"
FIXTURES = ROOT / "tests/fixtures/standard-assert-v1"


class AssertionSourceContract(unittest.TestCase):
    def test_typed_guest_failure_not_native_abort(self):
        source = ASSERT.read_text(encoding="utf-8")
        self.assertIn("class AssertionError extends Error", source)
        self.assertEqual(source.count("throw new AssertionError("), 5)
        for forbidden in ("rt fail(", "abort(", "panic(", "raise("):
            self.assertNotIn(forbidden, source)

    def test_static_qualified_api_expected_first(self):
        source = ASSERT.read_text(encoding="utf-8")
        for expected in (
            "define module assert as",
            "pub fnc true(bool condition): void",
            "pub fnc false(bool condition): void",
            "pub fnc equal<T: Eq>(T expected, T actual): void",
            "pub fnc not_equal<T: Eq>(T expected, T actual): void",
            "pub fnc fail(String message): void",
            "expected eq actual",
        ):
            self.assertIn(expected, source)
        self.assertNotIn("(Any expected, Any actual)", source)

    def test_test_layer_reuses_runner_outcome(self):
        source = CHECK.read_text(encoding="utf-8")
        self.assertIn("import class TestOutcome from 'ores.std/testing'", source)
        self.assertIn("import fnc pass, fail from 'ores.std/testing'", source)
        self.assertNotIn("define pub class TestOutcome", source)
        for fragment in (
            "lower_inclusive > upper_inclusive",
            "actual < lower_inclusive",
            "actual > upper_inclusive",
            "return fail(message)",
        ):
            self.assertIn(fragment, source)
        self.assertNotIn("rt fail(", source)

    def test_explicit_library_registry_preserves_legacy(self):
        source = REGISTRY.read_text(encoding="utf-8")
        for fragment in ('"std/assert.atto"', '"std/test_assert.atto"', '"assert.atto"'):
            self.assertIn(fragment, source)

    def test_fixtures_are_uncredited(self):
        for name in ("pass.atto", "fail-unequal.atto", "reject-mixed-eq.atto", "test-outcome.atto"):
            self.assertIn("NOT_EXECUTED", (FIXTURES / name).read_text(encoding="utf-8"))
        success = (FIXTURES / "pass.atto").read_text(encoding="utf-8")
        self.assertIn('import module assert from pkg "std.assert"', success)
        for member in ("assert.true(", "assert.false(", "assert.equal(", "assert.not_equal("):
            self.assertIn(member, success)


if __name__ == "__main__":
    unittest.main()

import importlib.util
from dataclasses import replace
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    import sys
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


model = load("actor_model", BASE / "formal/check_actor_model.py")
lint = load("oreslang_lint", BASE / "oreslang_lint.py")


class ActorModelTests(unittest.TestCase):
    def test_all_interleavings_small_model(self):
        states, edges = model.explore(actors=2, messages=2, workers=2, capacity=1)
        self.assertGreater(states, 500)
        self.assertGreater(edges, states)

    def test_same_actor_cannot_have_two_carriers(self):
        s = model.initial(1, 1, 2)
        with self.assertRaisesRegex(AssertionError, "two carriers"):
            model.invariant(replace(s, workers=(0, 0)), 1)

    def test_duplicate_message_detected(self):
        s = model.initial(2, 1, 1)
        with self.assertRaisesRegex(AssertionError, "message in two"):
            model.invariant(replace(s, mail=((0,), (0,)), status=("queued",)), 1)

    def test_cannot_accept_after_end(self):
        s = model.initial(1, 1, 1)
        _, ended = next((e for e in model.transitions(s, 1) if e[0] == "end(0)"))
        self.assertFalse(any(a.startswith("send") for a, _ in model.transitions(ended, 1)))

    def test_budget_failure_not_reported_as_success(self):
        with self.assertRaisesRegex(RuntimeError, "NOT exhaustive"):
            model.explore(max_states=2)


class SourceLintTests(unittest.TestCase):
    def test_defer_inside_loop_with_nested_scope(self):
        src = "pub async routine go(): void { loop { if true { defer clean(); } } }"
        self.assertEqual([x.code for x in lint.lint(src)], ["ORES101"])

    def test_comments_and_strings_are_not_code(self):
        src = 'pub fnc f(): void { // defer await\\n\n val s = "defer await"; /* defer */ return; }'
        self.assertEqual(lint.lint(src), [])

    def test_sync_await(self):
        src = "pub fnc f(): void { if true { await wait(); } }"
        self.assertEqual([x.code for x in lint.lint(src)], ["ORES102"])

    def test_async_await(self):
        self.assertEqual(lint.lint("pub async routine go(): void { await wait(); }"), [])

    def test_defer_outside_loop(self):
        self.assertEqual(lint.lint("fnc f(): void { defer close(); return; }"), [])

    def test_optional_select_advisory(self):
        src = "async routine main(): void { select { readch(a) => 1; } }"
        self.assertEqual(lint.lint(src), [])
        self.assertEqual([x.code for x in lint.lint(src, pedantic=True)], ["ORES103"])

    def test_match_booleans(self):
        src = "fnc f(): void { match x { true => 1; false => 2; } }"
        self.assertEqual(lint.lint(src, pedantic=True), [])


if __name__ == "__main__":
    unittest.main()

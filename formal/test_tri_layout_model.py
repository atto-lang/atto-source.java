"""Exhaustive single/pair/triple layout checks with required negative mutants."""
import unittest
from tri_layout_model import (
    LAYOUTS, MUTANTS, CAP, FIELDS, INIT_ROWS, RegionPermit, all_subsets,
    admit_permit, concurrent_permits, required_bytes, cells, check_geometry,
    decode, encode, explore, invariant, initial, offset, next_states,
)


class TriLayoutFormalTest(unittest.TestCase):
    def test_all_seven_residency_combinations(self):
        combinations = all_subsets()
        self.assertEqual(len(combinations), 7)
        total_states = 0
        total_edges = 0
        for resident in combinations:
            with self.subTest(resident=resident):
                result = explore(resident)
                self.assertIsNone(result["failure"], result["trace"])
                self.assertGreater(result["states"], 50)
                self.assertGreater(result["transitions"], result["states"])
                self.assertIn("two-commits", result["coverage"])
                self.assertIn("two-readers", result["coverage"])
                if len(resident) >= 2:
                    self.assertIn("authority-migrated", result["coverage"])
                    self.assertIn("stale-cache", result["coverage"])
                total_states += result["states"]
                total_edges += result["transitions"]
        self.assertGreater(total_states, 6000)
        self.assertGreater(total_edges, 14000)

    def test_actual_byte_offsets_partial_aosoa_tile(self):
        check_geometry()
        self.assertEqual(cells("aos"), 6)
        self.assertEqual(cells("soa"), 6)
        self.assertEqual(cells("aosoa"), 8)
        self.assertEqual(offset("aosoa", 2, 0), 4)
        self.assertEqual(offset("aosoa", 2, 1), 6)
        self.assertEqual(set(range(8)) - {
            offset("aosoa", r, f) for r in range(CAP) for f in range(FIELDS)
        }, {5, 7})
        self.assertEqual(encode(INIT_ROWS, "aosoa")[5], -99)
        self.assertEqual(decode(encode(INIT_ROWS, "aosoa"), "aosoa"), INIT_ROWS)

    def test_abort_preserves_canonical_and_all_physical_caches(self):
        for resident in all_subsets():
            s = initial(resident)
            for layout in resident:
                started = dict(next_states(s))[f"begin_write:{layout}"]
                aborted = dict(next_states(started))["abort"]
                self.assertEqual(aborted.oracle, s.oracle)
                self.assertEqual(aborted.physical, s.physical)
                self.assertEqual(aborted.stamps, s.stamps)
                self.assertEqual(aborted.epoch, s.epoch)
                self.assertIsNone(invariant(aborted))

    def test_ecs_permits_one_authority_even_with_two_actor_groups(self):
        for resident in all_subsets():
            s = initial(resident)
            first, second = resident[0], resident[-1]
            ctx = dict(owner=7, generation=1, tenant=1, region=10)

            def permit(layout, mode="r", field=0, owner=7, epoch=0,
                       generation=1, tenant=1):
                return RegionPermit(owner, generation, tenant, 10, layout, epoch,
                                    mode, (0, 3), frozenset({field}))

            self.assertTrue(concurrent_permits(permit(first), permit(second), s, **ctx))
            self.assertFalse(concurrent_permits(permit(first, "w", 0),
                                               permit(second, "w", 1), s, **ctx))
            self.assertFalse(concurrent_permits(permit(first, "w"),
                                               permit(second), s, **ctx))
            for forged in (permit(first, owner=8), permit(first, generation=2),
                           permit(first, tenant=3), permit(first, epoch=1)):
                self.assertFalse(admit_permit(forged, s, **ctx))
            absent = next((l for l in LAYOUTS if l not in resident), None)
            if absent:
                self.assertFalse(admit_permit(permit(absent), s, **ctx))
            if len(resident) > 1:
                started = dict(next_states(s))[f"begin_write:{first}"]
                committed = next(v for action, v in next_states(started)
                                 if action.startswith("commit:"))
                self.assertFalse(admit_permit(permit(first, epoch=0), committed, **ctx))
                if second != first:
                    self.assertFalse(admit_permit(permit(second, epoch=1), committed, **ctx))

    def test_byte_budget_counts_all_copies_padding_and_scratch(self):
        totals = {}
        for resident in all_subsets():
            bound = required_bytes(3, 2, 8, 2, resident)
            totals[resident] = bound
            self.assertEqual(required_bytes(3, 2, 8, 2, resident,
                                            max_bytes=bound), bound)
            with self.assertRaises(OverflowError):
                required_bytes(3, 2, 8, 2, resident, max_bytes=bound - 1)
        for a in totals:
            for b in totals:
                if set(a) < set(b):
                    self.assertLess(totals[a], totals[b])
        with self.assertRaises(OverflowError):
            required_bytes(1 << 63, 32, 8, 32, LAYOUTS)
        for bad in ((), ("aos", "aos"), ("nonexistent",)):
            with self.assertRaises(ValueError):
                required_bytes(3, 2, 8, 2, bad)

    def test_mutant_counterexamples(self):
        expected = {
            "stale-read": "NoStaleStrongReads",
            "skip-reconcile": "CurrentLayoutMatchesLogicalState",
            "false-current-epoch": "CurrentLayoutMatchesLogicalState",
            "second-writer": "ExclusiveMutationPermit",
            "partial-abort": "CurrentLayoutMatchesLogicalState",
            "bad-tile-offset": "CurrentLayoutMatchesLogicalState",
        }
        self.assertEqual(set(MUTANTS), set(expected))
        for mutant, invariant_name in expected.items():
            with self.subTest(mutant=mutant):
                traces = [explore(resident, mutant) for resident in all_subsets()]
                caught = [x for x in traces if x["failure"] == invariant_name]
                self.assertTrue(caught, (mutant, traces))
                self.assertTrue(all(x["trace"] for x in caught))
                if mutant in ("stale-read", "skip-reconcile", "false-current-epoch"):
                    self.assertTrue(all(len(x["resident"]) >= 2 for x in caught))
                if mutant == "bad-tile-offset":
                    self.assertTrue(all("aosoa" in x["resident"] for x in caught))


if __name__ == "__main__":
    unittest.main()

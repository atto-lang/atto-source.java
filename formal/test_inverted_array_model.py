import inverted_array_model as m
import unittest

class InvertedArrayFormalTest(unittest.TestCase):
    def test_safe_exploration_and_witnesses(self):
        states, edges, error, trace, witnesses = m.explore()
        self.assertIsNone(error, trace)
        self.assertGreater(states, 100)
        self.assertGreater(edges, states)
        self.assertEqual(witnesses, {"disjoint-field-loans", "shared-read-loans", "slot-reuse", "two-live-rows"})

    def test_negative_controls(self):
        expected = {
            "aba": "HandleIdentityABA",
            "swap-identity": "DenseSlotBijection",
            "borrow-alias": "FieldBorrowConflict",
            "structural-borrow": "NoStructuralMutationDuringBorrow",
            "foreign-owner": "ForeignOwnerRejected",
            "slot-bounds": "InvalidHandleSlotRejected",
        }
        for mutant, property_name in expected.items():
            with self.subTest(mutant=mutant):
                _, _, error, trace, _ = m.explore(mutant)
                self.assertEqual(error, property_name)
                self.assertTrue(trace)

if __name__ == "__main__":
    unittest.main()

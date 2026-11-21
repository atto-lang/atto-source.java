"""Reference-only topology + ECS access policy tests.

This is a pure policy oracle, not Oreslang compiler/runtime execution.
Run: python3 -m unittest discover -s tests/reference -p 'test_layout_affinity_contract.py'
"""
import unittest
from dataclasses import dataclass


@dataclass(frozen=True)
class Worker:
    ident: int
    llc: int
    numa: int
    allowed: bool = True
    healthy: bool = True


def candidate_workers(workers, preferred, home_numa, home_llc):
    """Return locality preference; NEVER an ownership authorization."""
    eligible = [w for w in workers if w.allowed and w.healthy]
    return sorted(eligible, key=lambda w: (
        0 if w.ident == preferred else
        1 if w.llc == home_llc else
        2 if w.numa == home_numa else 3,
        w.ident,
    ))


@dataclass(frozen=True)
class Loan:
    allocation: int
    generation: int
    owner: int
    owner_epoch: int
    field: str
    start: int
    stop: int
    writable: bool


def may_coexist(left: Loan, right: Loan) -> bool:
    if left.start < 0 or left.stop <= left.start:
        return False
    if right.start < 0 or right.stop <= right.start:
        return False
    # Unknown/mismatched authority always fails closed, even for READ/READ.
    if (left.allocation != right.allocation or
        left.generation != right.generation or
        left.owner != right.owner or
        left.owner_epoch != right.owner_epoch):
        return False
    if left.field != right.field or left.stop <= right.start or right.stop <= left.start:
        return True
    return not (left.writable or right.writable)


def may_convert_layout(active_loans: int, active_turns: int) -> bool:
    return active_loans == 0 and active_turns == 0


class ContractTests(unittest.TestCase):
    def test_preferred_worker_and_llc_before_numa(self):
        w = [Worker(1, 9, 2), Worker(2, 7, 2), Worker(3, 9, 3)]
        self.assertEqual([x.ident for x in candidate_workers(w, 3, 2, 9)], [3, 1, 2])

    def test_disallowed_or_unhealthy_cpu_cannot_be_chosen(self):
        w = [Worker(0, 0, 0, allowed=False), Worker(1, 0, 0, healthy=False),
             Worker(2, 1, 1)]
        self.assertEqual([x.ident for x in candidate_workers(w, 0, 0, 0)], [2])

    def test_read_read_same_field(self):
        a = Loan(1, 4, 8, 2, "x", 0, 64, False)
        b = Loan(1, 4, 8, 2, "x", 16, 32, False)
        self.assertTrue(may_coexist(a, b))

    def test_write_read_overlap_rejected(self):
        a = Loan(1, 4, 8, 2, "x", 0, 64, True)
        b = Loan(1, 4, 8, 2, "x", 16, 32, False)
        self.assertFalse(may_coexist(a, b))

    def test_disjoint_rows_and_fields(self):
        a = Loan(1, 4, 8, 2, "x", 0, 64, True)
        self.assertTrue(may_coexist(a, Loan(1, 4, 8, 2, "x", 64, 128, True)))
        self.assertTrue(may_coexist(a, Loan(1, 4, 8, 2, "y", 0, 64, True)))

    def test_owner_epoch_and_generation_fail_closed(self):
        a = Loan(1, 4, 8, 2, "x", 0, 64, False)
        self.assertFalse(may_coexist(a, Loan(1, 4, 8, 3, "x", 0, 64, False)))
        self.assertFalse(may_coexist(a, Loan(1, 5, 8, 2, "x", 0, 64, False)))
        self.assertFalse(may_coexist(a, Loan(1, 4, 9, 2, "x", 0, 64, False)))

    def test_bad_intervals_rejected(self):
        a = Loan(1, 4, 8, 2, "x", 0, 64, False)
        self.assertFalse(may_coexist(a, Loan(1, 4, 8, 2, "x", 5, 5, False)))

    def test_layout_conversion_barrier(self):
        self.assertFalse(may_convert_layout(1, 0))
        self.assertFalse(may_convert_layout(0, 1))
        self.assertTrue(may_convert_layout(0, 0))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Bounded reference model for InvertedArray stable handles and column loans.
No claim about Java/LLVM guest execution or compiler lowering.
"""
from __future__ import annotations
from collections import deque
from dataclasses import dataclass, replace
import argparse
import json

CAP = 2
IDS = (1, 2, 3)
FIELDS = ("x", "y")
MUTANTS = ("aba", "swap-identity", "borrow-alias", "structural-borrow", "foreign-owner", "slot-bounds")

@dataclass(frozen=True)
class S:
    # Slot indirection is independent of dense physical row position.
    rows: tuple[int, ...] = (0, 0)
    gens: tuple[int, ...] = (1, 1)
    dense: tuple[int, ...] = ()
    inserted: frozenset[int] = frozenset()
    # (owner, slot, incarnation, expected logical row)
    issued: frozenset[tuple[int, int, int, int]] = frozenset()
    # Canonical multiset of (slot, field, read/write mode); two readers of
    # the same field remain two independent live loans and release separately.
    loans: tuple[tuple[int, str, str], ...] = ()
    structural_corrupt: bool = False
    owner: int = 7

def tupset(src, idx, val):
    out = list(src)
    out[idx] = val
    return tuple(out)

def resolves(s, handle, unsafe_owner=False, unsafe_bounds=False):
    owner, slot, gen, logical = handle
    if not unsafe_owner and owner != s.owner:
        return None
    if not unsafe_bounds and not 0 <= slot < CAP:
        return None
    if s.gens[slot] != gen or s.rows[slot] == 0:
        return None
    return s.rows[slot]

def conflicts(loans):
    arr = sorted(loans)
    return any(a[0] == b[0] and a[1] == b[1] and
               (a[2] == "w" or b[2] == "w")
               for i, a in enumerate(arr) for b in arr[i+1:])

def nexts(s, mutant=None):
    if 0 in s.rows and len(s.inserted) < len(IDS) and (not s.loans or mutant == "structural-borrow"):
        slot = s.rows.index(0)
        for ident in IDS:
            if ident in s.inserted:
                continue
            yield f"insert({ident})", replace(
                s, rows=tupset(s.rows, slot, ident), dense=s.dense+(slot,),
                inserted=s.inserted|{ident},
                issued=s.issued|{(s.owner, slot, s.gens[slot], ident)},
                structural_corrupt=s.structural_corrupt or bool(s.loans))
    for slot in s.dense:
        if s.loans and mutant != "structural-borrow":
            continue
        pos = s.dense.index(slot)
        last = s.dense[-1]
        arr = list(s.dense)
        arr[pos] = last
        arr.pop()
        if mutant == "swap-identity" and slot != last:
            # Broken implementation swaps handle-slot values, not only dense indices.
            rows = tupset(tupset(s.rows, slot, s.rows[last]), last, 0)
            gens = tupset(s.gens, last, s.gens[last]+1)
        else:
            rows = tupset(s.rows, slot, 0)
            gens = tupset(s.gens, slot, s.gens[slot]+(0 if mutant == "aba" else 1))
        yield f"erase_swap(slot={slot})", replace(
            s, rows=rows, gens=gens, dense=tuple(arr),
            structural_corrupt=s.structural_corrupt or bool(s.loans))
    if len(s.loans) < 2:
        for slot in s.dense:
            for field in FIELDS:
                for mode in ("r", "w"):
                    loan = (slot, field, mode)
                    proposed = tuple(sorted(s.loans + (loan,)))
                    if not conflicts(proposed) or mutant == "borrow-alias":
                        yield f"borrow({slot},{field},{mode})", replace(s, loans=proposed)
    for i, loan in enumerate(s.loans):
        if i > 0 and loan == s.loans[i-1]:
            continue
        yield f"release({loan})", replace(s, loans=s.loans[:i] + s.loans[i+1:])

def violation(s, mutant=None):
    if len(s.dense) != len(set(s.dense)) or len(s.dense) > CAP:
        return "DenseUniqueness"
    if set(s.dense) != {i for i, v in enumerate(s.rows) if v != 0}:
        return "DenseSlotBijection"
    if len(set(v for v in s.rows if v)) != len([v for v in s.rows if v]):
        return "UniqueRows"
    if any(resolves(s, h) not in (None, h[3]) for h in s.issued):
        return "HandleIdentityABA"
    if any(resolves(s, (s.owner + 1, h[1], h[2], h[3]),
                    unsafe_owner=(mutant == "foreign-owner")) is not None
           for h in s.issued):
        return "ForeignOwnerRejected"
    if any(resolves(s, (s.owner, -1, h[2], h[3]),
                    unsafe_bounds=(mutant == "slot-bounds")) is not None
           or resolves(s, (s.owner, CAP, h[2], h[3])) is not None
           for h in s.issued):
        return "InvalidHandleSlotRejected"
    if conflicts(s.loans):
        return "FieldBorrowConflict"
    if s.structural_corrupt or any(s.rows[l[0]] == 0 for l in s.loans):
        return "NoStructuralMutationDuringBorrow"
    if any(g <= 0 for g in s.gens):
        return "GenerationType"
    return None

def explore(mutant=None):
    if mutant not in (*MUTANTS, None):
        raise ValueError(mutant)
    start = S()
    parents = {start: None}
    q = deque([start])
    edges = 0
    witness = set()
    while q:
        s = q.popleft()
        if len(s.loans) == 2 and not conflicts(s.loans):
            loans = tuple(s.loans)
            if any(l[2] == "w" for l in loans) and loans[0][1] != loans[1][1]:
                witness.add("disjoint-field-loans")
            if all(l[2] == "r" for l in loans) and loans[0][:2] == loans[1][:2]:
                witness.add("shared-read-loans")
        if any(s.rows[slot] != 0 and s.gens[slot] > 1 for slot in range(CAP)):
            witness.add("slot-reuse")
        if len(s.dense) == 2 and s.rows[s.dense[0]] and s.rows[s.dense[1]]:
            witness.add("two-live-rows")
        for action, nxt in nexts(s, mutant):
            edges += 1
            if nxt in parents:
                continue
            parents[nxt] = (s, action)
            bad = violation(nxt, mutant)
            if bad:
                trace = [action]
                cur = s
                while parents[cur] is not None:
                    cur, a = parents[cur]
                    trace.append(a)
                return len(parents), edges, bad, tuple(reversed(trace)), witness
            q.append(nxt)
    return len(parents), edges, None, (), witness

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--mutant", choices=MUTANTS)
    a = p.parse_args()
    states, edges, bad, trace, witness = explore(a.mutant)
    print(json.dumps({"model":"inverted-array-v1","states":states,"transitions":edges,
                      "mutant":a.mutant or "none","violation":bad,
                      "counterexample":trace,"witnesses":sorted(witness)}))
    return 1 if bad else 0

if __name__ == "__main__":
    raise SystemExit(main())

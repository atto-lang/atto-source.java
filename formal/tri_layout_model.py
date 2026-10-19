#!/usr/bin/env python3
"""Bounded exhaustive reference for 1/2/3 resident AoS, SoA, AoSoA layouts.

Three logical rows cross an AoSoA tile-2 boundary; two fields per row.
A single physical layout is authoritative per committed epoch; others are
versioned caches. Neither model nor host-Java test proves guest lowering.
"""
from __future__ import annotations
import argparse
from collections import deque
from dataclasses import dataclass, replace
from itertools import combinations
import json

LAYOUTS = ("aos", "soa", "aosoa")
CAP, FIELDS, TILE = 3, 2, 2
PAD, MAX_COMMITS = -99, 2
MUTANTS = ("stale-read", "skip-reconcile", "false-current-epoch",
           "second-writer", "partial-abort", "bad-tile-offset")
INIT_ROWS = ((0, 1), (1, 0), (1, 1))


def offset(layout, row, field, mutant=None):
    assert 0 <= row < CAP and 0 <= field < FIELDS
    if layout == "aos":
        return row * FIELDS + field
    if layout == "soa":
        return field * CAP + row
    if layout == "aosoa":
        if mutant == "bad-tile-offset":
            return (row // TILE + field) * TILE + row % TILE
        return (row // TILE * FIELDS + field) * TILE + row % TILE
    raise ValueError(layout)


def cells(layout):
    return CAP * FIELDS if layout != "aosoa" else ((CAP + TILE - 1) // TILE) * TILE * FIELDS


def encode(rows, layout, mutant=None):
    a = [PAD] * cells(layout)
    for r, row in enumerate(rows):
        for f, value in enumerate(row):
            a[offset(layout, r, f, mutant)] = value
    return tuple(a)


def decode(physical, layout):
    return tuple(tuple(physical[offset(layout, r, f)] for f in range(FIELDS))
                 for r in range(CAP))


@dataclass(frozen=True)
class State:
    resident: tuple[str, ...]
    oracle: tuple[tuple[int, ...], ...]
    physical: tuple[tuple[int, ...] | None, ...]
    stamps: tuple[int, ...]
    epoch: int = 0
    authority: str = "aos"
    readers: tuple[str, ...] = ()
    writer: str | None = None
    staged: tuple[tuple[int, ...], ...] | None = None


def initial(resident):
    resident = tuple(resident)
    return State(resident, INIT_ROWS,
                 tuple(encode(INIT_ROWS, l) if l in resident else None for l in LAYOUTS),
                 tuple(0 if l in resident else -1 for l in LAYOUTS), 0, resident[0])


def update_tuple(t, index, value):
    a = list(t)
    a[index] = value
    return tuple(a)


def hydrate(s, layout, mutant=None):
    """Read from physical authority, not the independent logical oracle."""
    if layout not in s.resident:
        raise ValueError("not resident")
    li = LAYOUTS.index(layout)
    if s.stamps[li] == s.epoch:
        return s
    authority = decode(s.physical[LAYOUTS.index(s.authority)], s.authority)
    return replace(s,
                   physical=update_tuple(s.physical, li, encode(authority, layout, mutant)),
                   stamps=update_tuple(s.stamps, li, s.epoch))


def next_states(s, mutant=None):
    # All-or-nothing full-region refresh; pinned views block transpose.
    if not s.readers and s.writer is None:
        for layout in s.resident:
            if s.stamps[LAYOUTS.index(layout)] != s.epoch:
                yield f"reconcile:{layout}", hydrate(s, layout, mutant)
    if s.writer is None:
        if len(s.readers) < 2:
            for layout in s.resident:
                if s.stamps[LAYOUTS.index(layout)] == s.epoch:
                    yield f"borrow_read:{layout}", replace(s, readers=s.readers + (layout,))
        if s.readers:
            for layout in set(s.readers):
                r = list(s.readers)
                r.remove(layout)
                yield f"release_read:{layout}", replace(s, readers=tuple(r))
        if not s.readers and s.epoch < MAX_COMMITS:
            for layout in s.resident:
                if s.stamps[LAYOUTS.index(layout)] == s.epoch or mutant == "skip-reconcile":
                    stage = decode(s.physical[LAYOUTS.index(layout)], layout)
                    yield f"begin_write:{layout}", replace(s, writer=layout, staged=stage)
        if mutant == "second-writer" and s.readers:
            yield "MUTANT.writer_during_read", replace(s, writer=s.resident[0], staged=s.oracle)
    else:
        if mutant == "second-writer" and s.epoch < MAX_COMMITS:
            yield "MUTANT.second_writer", replace(s, readers=s.readers + (s.writer,))
        assert s.staged is not None
        for r in range(CAP):
            for f in range(FIELDS):
                candidate = tuple(update_tuple(row, f, 1 - row[f]) if i == r else row
                                  for i, row in enumerate(s.staged))
                next_epoch = s.epoch + 1
                li = LAYOUTS.index(s.writer)
                physical = update_tuple(s.physical, li, encode(candidate, s.writer, mutant))
                stamps = update_tuple(s.stamps, li, next_epoch)
                if mutant == "false-current-epoch":
                    stamps = tuple(next_epoch if l in s.resident else -1 for l in LAYOUTS)
                logical = tuple(update_tuple(row, f, 1 - row[f]) if i == r else row
                                for i, row in enumerate(s.oracle))
                yield f"commit:{s.writer}:{r}:{f}", replace(
                    s, oracle=logical, physical=physical, stamps=stamps, epoch=next_epoch,
                    authority=s.writer, writer=None, staged=None)
        yield "abort", replace(s, writer=None, staged=None)
        if mutant == "partial-abort":
            li = LAYOUTS.index(s.writer)
            corrupt = tuple(update_tuple(row, 0, 1-row[0]) if i == 0 else row
                            for i, row in enumerate(s.staged))
            yield "MUTANT.partial_abort", replace(
                s, writer=None, staged=None,
                physical=update_tuple(s.physical, li, encode(corrupt, s.writer)))
    if mutant == "stale-read" and s.writer is None:
        for l in s.resident:
            if s.stamps[LAYOUTS.index(l)] != s.epoch:
                yield f"MUTANT.read_stale:{l}", replace(s, readers=s.readers + (f"stale:{l}",))


def invariant(s):
    if s.authority not in s.resident or s.epoch not in range(MAX_COMMITS + 1):
        return "AuthorityType"
    if s.stamps[LAYOUTS.index(s.authority)] != s.epoch:
        return "AuthorityIsCurrent"
    if len(s.readers) > 2 or (s.writer is not None and s.readers):
        return "ExclusiveMutationPermit"
    if any(l.startswith("stale:") or l not in s.resident for l in s.readers):
        return "NoStaleStrongReads"
    if (s.writer is None) != (s.staged is None):
        return "TransactionLifecycle"
    for layout in LAYOUTS:
        i = LAYOUTS.index(layout)
        physical = s.physical[i]
        if layout not in s.resident:
            if physical is not None or s.stamps[i] != -1:
                return "AbsentLayoutHasNoStorage"
        else:
            if physical is None or len(physical) != cells(layout):
                return "PhysicalGeometry"
            if s.stamps[i] == s.epoch and decode(physical, layout) != s.oracle:
                return "CurrentLayoutMatchesLogicalState"
            if not 0 <= s.stamps[i] <= s.epoch:
                return "StampMonotonicity"
    return None


def explore(resident, mutant=None, max_states=150000):
    start = initial(resident)
    parents = {start: None}
    q = deque([start])
    edges = 0
    coverage = set()
    while q:
        s = q.popleft()
        if len(s.resident) > 1 and any(
                s.stamps[LAYOUTS.index(l)] < s.epoch for l in s.resident):
            coverage.add("stale-cache")
        if s.epoch == 2:
            coverage.add("two-commits")
        if s.writer is not None:
            coverage.add("open-transaction")
        if len(s.readers) == 2:
            coverage.add("two-readers")
        if len(s.resident) > 1 and s.authority != s.resident[0]:
            coverage.add("authority-migrated")
        for action, target in next_states(s, mutant):
            edges += 1
            error = invariant(target)
            if error:
                trace = [action]
                cur = s
                while parents[cur] is not None:
                    cur, step = parents[cur]
                    trace.append(step)
                return {"resident": list(resident), "states": len(parents),
                        "transitions": edges, "failure": error,
                        "trace": list(reversed(trace)), "coverage": sorted(coverage)}
            if target not in parents:
                parents[target] = (s, action)
                q.append(target)
                if len(parents) > max_states:
                    raise RuntimeError(f"model bound exceeded: {resident}")
    return {"resident": list(resident), "states": len(parents),
            "transitions": edges, "failure": None, "trace": [],
            "coverage": sorted(coverage)}


def all_subsets():
    return [subset for size in (1, 2, 3) for subset in combinations(LAYOUTS, size)]


def check_geometry():
    for layout in LAYOUTS:
        addrs = [offset(layout, r, f) for r in range(CAP) for f in range(FIELDS)]
        assert len(set(addrs)) == CAP * FIELDS, layout
        assert all(0 <= v < cells(layout) for v in addrs), layout
        assert decode(encode(INIT_ROWS, layout), layout) == INIT_ROWS, layout
    assert cells("aosoa") > CAP * FIELDS


def required_bytes(capacity, fields, width, tile, resident, *,
                   max_bytes=(1 << 63) - 1, atomic_refresh=True):
    """Conservative payload + reference metadata + optional transpose scratch."""
    if capacity <= 0 or fields <= 0 or width <= 0 or tile <= 0:
        raise ValueError("invalid layout geometry")
    if not resident or len(set(resident)) != len(resident) or any(l not in LAYOUTS for l in resident):
        raise ValueError("invalid residency")
    if max_bytes <= 0:
        raise ValueError("invalid budget")
    entries = {
        "aos": capacity * fields * width,
        "soa": capacity * fields * width,
        "aosoa": ((capacity + tile - 1) // tile) * tile * fields * width,
    }
    metadata = capacity * 24 + 3 * 8
    scratch = max(entries[l] for l in resident) if atomic_refresh and len(resident) > 1 else 0
    n = sum(entries[l] for l in resident) + metadata + scratch
    if n > max_bytes:
        raise OverflowError("capacity/budget exceeded")
    return n


@dataclass(frozen=True)
class RegionPermit:
    """Compiler/runtime-issued authority, never ActorGroup membership."""
    owner: int
    generation: int
    tenant: int
    region: int
    layout: str
    epoch: int
    mode: str
    rows: tuple[int, int]
    fields: frozenset[int]


def admit_permit(p, state, *, owner, generation, tenant, region):
    return (p.owner == owner and p.generation == generation and
            p.tenant == tenant and p.region == region and
            p.layout in state.resident and p.epoch == state.epoch and
            state.stamps[LAYOUTS.index(p.layout)] == state.epoch and
            p.mode in ("r", "w") and
            0 <= p.rows[0] < p.rows[1] <= CAP and
            bool(p.fields) and p.fields <= frozenset(range(FIELDS)) and
            state.writer is None and (p.mode == "r" or not state.readers))


def concurrent_permits(a, b, state, *, owner, generation, tenant, region):
    if not all(admit_permit(p, state, owner=owner, generation=generation,
                            tenant=tenant, region=region) for p in (a, b)):
        return False
    # One region-wide authority: even disjoint writer fields/rows in separate
    # caches must serialize until an atomic field-range merge is implemented.
    return a.mode == b.mode == "r"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mutant", choices=MUTANTS)
    args = parser.parse_args()
    check_geometry()
    records = [explore(combination, args.mutant) for combination in all_subsets()]
    print(json.dumps(records, sort_keys=True))
    return int(any(x["failure"] is not None for x in records))


if __name__ == "__main__":
    raise SystemExit(main())

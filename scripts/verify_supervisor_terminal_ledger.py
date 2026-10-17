#!/usr/bin/env python3
"""Finite model for prepaid supervisor terminal CONTROL.

This is source-free reference evidence only; it does not execute ActorRuntime.
"""

from collections import deque
from itertools import product

FREE = 0
RESERVED = 1
READY = 2


def explore(capacity=2, depth=7):
    start = ((FREE,) * capacity, ())
    seen = {start}
    frontier = deque([(start, 0)])
    transitions = 0

    while frontier:
        (slots, ready), steps = frontier.popleft()
        assert len(ready) <= capacity
        assert sum(1 for state in slots if state != FREE) <= capacity
        assert sum(1 for state in slots if state == READY) == len(ready)
        assert len(set(ready)) == len(ready)
        for index in ready:
            assert slots[index] == READY

        if steps == depth:
            continue

        candidates = []

        # Pre-reserve before child admission.
        for index, state in enumerate(slots):
            if state == FREE:
                updated = list(slots)
                updated[index] = RESERVED
                candidates.append((tuple(updated), ready))

        # A reserved child can always publish terminal CONTROL; no separate
        # queue-capacity test exists at this transition.
        for index, state in enumerate(slots):
            if state == RESERVED:
                updated = list(slots)
                updated[index] = READY
                candidates.append((tuple(updated), ready + (index,)))

        # Admission that never created a child may release RESERVED only.
        for index, state in enumerate(slots):
            if state == RESERVED:
                updated = list(slots)
                updated[index] = FREE
                candidates.append((tuple(updated), ready))

        # Only the ordered ready head may be acknowledged/released.
        if ready:
            index = ready[0]
            updated = list(slots)
            updated[index] = FREE
            candidates.append((tuple(updated), ready[1:]))

        for nxt in candidates:
            transitions += 1
            if nxt not in seen:
                seen.add(nxt)
                frontier.append((nxt, steps + 1))

    return len(seen), transitions


def saturated_publish_proof(capacity=3):
    slots = [RESERVED] * capacity
    ready = deque()
    for index in reversed(range(capacity)):
        assert slots[index] == RESERVED
        slots[index] = READY
        ready.append(index)
    assert len(ready) == capacity
    assert all(state == READY for state in slots)
    return len(ready)


def mutation_probe(capacity=2):
    # If terminal capacity were allocated only at publish time, two live
    # children plus one already queued receipt could overcommit a 2-slot lane.
    live_without_credit = 2
    queued_receipts = 1
    assert live_without_credit + queued_receipts > capacity


def main():
    states, transitions = explore()
    prepaid = saturated_publish_proof()
    mutation_probe()
    print(
        "SUPERVISOR_TERMINAL_LEDGER_MODEL_PASS "
        f"states={states} transitions={transitions} prepaid={prepaid}"
    )


if __name__ == "__main__":
    main()

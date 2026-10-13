#!/usr/bin/env python3
"""Dependency-free exhaustive exploration of VisibilityExports.tla's finite state.

This executable checker independently explores every reachable scan order
and validates its safety/completion invariants. It checks the abstract
contract, not the Java bytecode or its refinement to this model.

Usage: python3 verification/visibility/check_model.py
"""
from collections import deque

ROOT = "root"
PRIVATE_MODULE = "private_module"
PUBLIC_MODULE = "public_module"

DECLARATIONS = {
    "root_public": (ROOT, True),
    "root_private": (ROOT, False),
    "hidden_public": (PRIVATE_MODULE, True),
    "hidden_private": (PRIVATE_MODULE, False),
    "named_public": (PUBLIC_MODULE, True),
    "named_private": (PUBLIC_MODULE, False),
}


def specification_allows(declaration):
    module, public_member = DECLARATIONS[declaration]
    return public_member and module in {ROOT, PUBLIC_MODULE}


def check_model(policy, expect_safe=True):
    all_declarations = frozenset(DECLARATIONS)
    initial = (frozenset(), frozenset())  # scanned, exports
    visited = {initial}
    pending = deque([initial])
    edges = 0
    unsafe = []
    while pending:
        scanned, exported = pending.popleft()
        if not exported <= all_declarations:
            raise AssertionError("an export was fabricated")
        if not scanned <= all_declarations:
            raise AssertionError("a declaration was fabricated")
        if any(not specification_allows(d) for d in exported):
            unsafe.append((scanned, exported))
        if scanned == all_declarations:
            expected = frozenset(d for d in DECLARATIONS if specification_allows(d))
            if expect_safe and exported != expected:
                raise AssertionError(f"incomplete or extra exports: {exported} != {expected}")
        for d in all_declarations - scanned:
            next_state = (
                scanned | {d},
                exported | {d} if policy(d) else exported,
            )
            edges += 1
            if next_state not in visited:
                visited.add(next_state)
                pending.append(next_state)
    if expect_safe and unsafe:
        raise AssertionError(f"private declaration exposed: {unsafe[0]}")
    if not expect_safe and not unsafe:
        raise AssertionError("test mutation did not produce a policy violation")
    return len(visited), edges, len(unsafe)


def main():
    states, transitions, unsafe = check_model(specification_allows)
    assert (states, transitions, unsafe) == (64, 192, 0), (
        states, transitions, unsafe
    )
    # Fault injection proves the checker catches a realistic bug: checking
    # member pub but forgetting the containing module's private visibility.
    broken = lambda d: DECLARATIONS[d][1]
    broken_states, broken_transitions, counterexamples = check_model(
        broken, expect_safe=False
    )
    assert broken_states == 64 and broken_transitions == 192
    assert counterexamples > 0
    print(
        f"PASS: {states} states, {transitions} transitions, "
        f"0 private exposures; faulty-policy counterexamples={counterexamples}"
    )


if __name__ == "__main__":
    main()

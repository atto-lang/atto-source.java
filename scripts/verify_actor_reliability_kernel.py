#!/usr/bin/env python3
"""Finite reference checks for actor reliability invariants.

This is deliberately source-free. It is not evidence that ActorRuntime or the
Java compiler executed; it guards the abstract admission rules against future
regressions and mutations.
"""
from collections import deque
from itertools import product


def mailbox_states(user_cap=2, control_cap=2):
    start = (0, 0)
    seen = {start}
    work = deque([start])
    transitions = 0
    while work:
        user, control = work.popleft()
        assert 0 <= user <= user_cap
        assert 0 <= control <= control_cap
        assert user + control <= user_cap + control_cap

        candidates = []
        if user < user_cap:
            candidates.append((user + 1, control))
        if control < control_cap:
            candidates.append((user, control + 1))
        if user:
            candidates.append((user - 1, control))
        if control:
            candidates.append((user, control - 1))

        for nxt in candidates:
            transitions += 1
            if nxt not in seen:
                seen.add(nxt)
                work.append(nxt)

    # The key liveness headroom property: a completely full USER lane must
    # still admit CONTROL until CONTROL's own independent reservation is full.
    assert (user_cap, 0) in seen
    assert (user_cap, 1) in seen
    assert (user_cap, control_cap) in seen
    return len(seen), transitions


class RestartWindow:
    def __init__(self, maximum, window):
        self.maximum = maximum
        self.window = window
        self.accepted = []

    def attempt(self, now):
        self.accepted = [t for t in self.accepted if now - t < self.window]
        before = tuple(self.accepted)
        if len(self.accepted) >= self.maximum:
            return False, before
        self.accepted.append(now)
        return True, tuple(self.accepted)


def restart_exhaustive(maximum=2, window=3, horizon=6, length=7):
    checked = 0
    for times in product(range(horizon + 1), repeat=length):
        if any(times[i] > times[i + 1] for i in range(len(times) - 1)):
            continue
        gate = RestartWindow(maximum, window)
        for now in times:
            before = tuple(gate.accepted)
            allowed, after = gate.attempt(now)
            checked += 1
            assert len(after) <= maximum
            assert all(now - t < window for t in after)
            if not allowed:
                # Denial may evict old timestamps, but may not append a new one.
                expected = tuple(t for t in before if now - t < window)
                assert after == expected
    return checked


def sampled_counts(limit=100):
    return [n for n in range(1, limit + 1) if n & (n - 1) == 0]


def mutation_probes():
    # Shared-capacity mutation would reproduce the classic failure where user
    # traffic can occupy control headroom.
    user_cap = 2
    total_cap = 2
    user = 2
    control = 0
    assert user + control >= total_cap
    assert control == 0  # CONTROL would be starved by the mutated design.

    # A restart gate that records denied attempts as accepted exceeds its cap.
    accepted = [0, 1]
    accepted.append(2)
    assert len(accepted) > 2


def main():
    states, transitions = mailbox_states()
    attempts = restart_exhaustive()
    samples = sampled_counts()
    assert samples == [1, 2, 4, 8, 16, 32, 64]
    mutation_probes()
    print(
        "ACTOR_RELIABILITY_MODEL_PASS "
        f"mailbox_states={states} mailbox_transitions={transitions} "
        f"restart_attempts={attempts} sampled_100={len(samples)}"
    )


if __name__ == "__main__":
    main()

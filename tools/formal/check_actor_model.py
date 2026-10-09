#!/usr/bin/env python3
"""Bounded explicit-state model checker for Oreslang actor mailbox/await semantics.

This verifies an ABSTRACT transition system, not the JVM implementation.
All state spaces are finite, and exhaustion is required for a passing result.
"""
from __future__ import annotations

import argparse
from collections import deque
from dataclasses import dataclass, replace


@dataclass(frozen=True)
class State:
    alive: tuple[bool, ...]
    workers: tuple[int, ...]           # -1 idle, otherwise actor index
    mail: tuple[tuple[int, ...], ...]   # per-actor queued user-message IDs
    status: tuple[str, ...]             # new, queued, handled, discarded
    awaiting: tuple[bool, ...]         # suspended continuation exists
    ready: tuple[bool, ...]            # completed continuation queued


def initial(actors: int, messages: int, workers: int) -> State:
    return State((True,) * actors, (-1,) * workers,
                 ((),) * actors, ("new",) * messages,
                 (False,) * actors, (False,) * actors)


def set_at(items: tuple, index: int, item):
    out = list(items)
    out[index] = item
    return tuple(out)


def transitions(s: State, capacity: int):
    """Transitions include adversarial ordering of I/O, termination and carriers."""
    for actor, live in enumerate(s.alive):
        if live:
            for message, status in enumerate(s.status):
                if status == "new" and len(s.mail[actor]) < capacity:
                    yield (f"send({actor},{message})", replace(
                        s, mail=set_at(s.mail, actor, s.mail[actor] + (message,)),
                        status=set_at(s.status, message, "queued")))
            if s.awaiting[actor] and not s.ready[actor]:
                yield (f"io_complete({actor})", replace(
                    s, ready=set_at(s.ready, actor, True)))

        # Ending seals ingress, drops pending messages, cancels a continuation.
        if live:
            statuses = s.status
            for message in s.mail[actor]:
                statuses = set_at(statuses, message, "discarded")
            yield (f"end({actor})", replace(
                s, alive=set_at(s.alive, actor, False),
                mail=set_at(s.mail, actor, ()), status=statuses,
                awaiting=set_at(s.awaiting, actor, False),
                ready=set_at(s.ready, actor, False)))

    for worker, actor in enumerate(s.workers):
        if actor == -1:
            for target, live in enumerate(s.alive):
                # The execution lease prevents two carriers running an actor.
                if live and target not in s.workers and (
                    s.mail[target] or s.ready[target]
                ):
                    yield (f"acquire({worker},{target})", replace(
                        s, workers=set_at(s.workers, worker, target)))
            continue

        yield (f"release({worker})", replace(
            s, workers=set_at(s.workers, worker, -1)))
        if not s.alive[actor]:
            continue  # teardown may release, never run additional guest work
        if s.ready[actor]:
            # Continuation lane outranks ordinary user mail.
            yield (f"resume({worker},{actor})", replace(
                s, ready=set_at(s.ready, actor, False),
                awaiting=set_at(s.awaiting, actor, False)))
        elif s.mail[actor]:
            message = s.mail[actor][0]
            yield (f"receive({worker},{actor},{message})", replace(
                s, mail=set_at(s.mail, actor, s.mail[actor][1:]),
                status=set_at(s.status, message, "handled")))
        if not s.awaiting[actor]:
            # Await releases the carrier; I/O may complete later.
            yield (f"await({worker},{actor})", replace(
                s, awaiting=set_at(s.awaiting, actor, True),
                workers=set_at(s.workers, worker, -1)))


def invariant(s: State, capacity: int) -> None:
    active = [actor for actor in s.workers if actor >= 0]
    assert len(active) == len(set(active)), "two carriers own one actor"
    queued = [msg for inbox in s.mail for msg in inbox]
    assert len(queued) == len(set(queued)), "message in two mailboxes"
    assert all(len(inbox) <= capacity for inbox in s.mail), "mailbox overflow"
    assert set(s.status) <= {"new", "queued", "handled", "discarded"}
    assert set(queued) == {i for i, x in enumerate(s.status) if x == "queued"}, \
        "status/mailbox accounting disagreement"
    for actor, live in enumerate(s.alive):
        assert live or (not s.mail[actor] and not s.awaiting[actor]
                        and not s.ready[actor]), "work retained after termination"
        assert not s.ready[actor] or s.awaiting[actor], "orphan continuation"


def explore(actors=1, messages=2, workers=2, capacity=1, max_states=100_000):
    if min(actors, messages, workers, capacity, max_states) < 1:
        raise ValueError("all model bounds must be positive")
    first = initial(actors, messages, workers)
    discovered = {first: None}
    queue = deque([first])
    edges = 0
    while queue:
        state = queue.popleft()
        try:
            invariant(state, capacity)
        except AssertionError as exc:
            trace = []
            current = state
            while discovered[current] is not None:
                parent, label = discovered[current]
                trace.append(label)
                current = parent
            trace.reverse()
            raise AssertionError(f"counterexample: {' -> '.join(trace)}: {exc}") from exc
        for label, child in transitions(state, capacity):
            edges += 1
            if child in discovered:
                continue
            if len(discovered) >= max_states:
                raise RuntimeError("STATE_LIMIT_REACHED: exploration NOT exhaustive; "
                                   "increase --max-states or decrease model bounds")
            discovered[child] = (state, label)
            queue.append(child)
    return len(discovered), edges


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--actors", type=int, default=1)
    parser.add_argument("--messages", type=int, default=2)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--capacity", type=int, default=1)
    parser.add_argument("--max-states", type=int, default=100_000)
    args = parser.parse_args()
    states, edges = explore(**vars(args))
    print(f"BOUNDED MODEL PASS: exhaustively visited {states} abstract states "
          f"and {edges} transitions (actors={args.actors}, messages={args.messages}, "
          f"workers={args.workers}, mailbox_capacity={args.capacity})")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Small exhaustive model of one actor's scheduled/guest-exit/stop handshake.

This model checks a narrow scheduler invariant, not the JVM implementation or
fairness of the carrier pool. It intentionally replays the pre-fix rescheduler
rule to demonstrate the original stranded-done counterexample.
"""
from collections import deque
from dataclasses import dataclass, replace


@dataclass(frozen=True)
class State:
    stopped: bool = False
    pending: bool = False
    scheduled: bool = True
    carrier: bool = False
    guest_returned: bool = False
    revoked: bool = False
    finalized: bool = False
    tear_down_count: int = 0


def successors(s: State, retain_stopped_teardown_wake: bool):
    if s.finalized:
        return []
    out = []
    if not s.stopped:
        # Actor has completed initialization, and may now receive a host stop.
        out.append(("stop", replace(s, stopped=True, pending=True,
                                    scheduled=s.scheduled or not s.carrier)))
    if s.stopped and not s.revoked:
        out.append(("cancel", replace(s, revoked=True, pending=False)))
    if s.scheduled and not s.carrier:
        out.append(("carrier-enter", replace(s, carrier=True,
                                              guest_returned=False)))
    if s.carrier and not s.guest_returned:
        # Finally runs with the exclusive actor lease before turn return.
        run = s.pending and s.stopped and not s.revoked
        out.append(("guest-return", replace(s, guest_returned=True,
                   pending=False if run else s.pending,
                   tear_down_count=s.tear_down_count + int(run))))
    if s.carrier and s.guest_returned:
        keep = (retain_stopped_teardown_wake and s.pending
                and s.stopped and not s.revoked)
        if s.stopped:
            next_scheduled = bool(keep)
        else:
            next_scheduled = False  # No modeled user mail.
        out.append(("carrier-exit", replace(s, carrier=False,
                   scheduled=next_scheduled,
                   finalized=s.stopped and not s.pending)))
    if s.stopped and not s.scheduled and not s.carrier and not s.pending:
        out.append(("finalize", replace(s, finalized=True)))
    return out


def explore(retain_stopped_teardown_wake: bool):
    initial = State()
    queue = deque([(initial, ())])
    visited = {initial}
    stranded = None
    while queue:
        state, trace = queue.popleft()
        assert state.tear_down_count <= 1, (trace, state)
        assert not (state.finalized and state.pending), (trace, state)
        if (state.stopped and state.pending and not state.scheduled
                and not state.carrier and not state.finalized):
            stranded = (trace, state)
            break
        for action, newer in successors(state, retain_stopped_teardown_wake):
            if newer not in visited:
                visited.add(newer)
                queue.append((newer, trace + (action,)))
    return len(visited), stranded


def main():
    old_count, old_stuck = explore(False)
    assert old_stuck is not None, "old stop/TurnExecutor race was not reproduced"
    new_count, new_stuck = explore(True)
    assert new_stuck is None, f"stranded teardown in fixed model: {new_stuck}"
    print(f"PASS: pre-fix model stranded after: {', '.join(old_stuck[0])}")
    print(f"PASS: corrected model exhaustively explored {new_count} states")
    print("PASS: at most one teardown; no finalized-with-pending state")
    print(f"Pre-fix model reached {old_count} states before counterexample")


if __name__ == "__main__":
    main()

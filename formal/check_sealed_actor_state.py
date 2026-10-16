#!/usr/bin/env python3
"""Finite-state exploration of a sealed actor-state loan protocol.

Run: python3 formal/check_sealed_actor_state.py

Proof boundary: validates this ABSTRACT protocol, not JVM/LLVM codegen,
FFI exclusion, heap provenance or scheduler correctness.
"""
from collections import deque
from dataclasses import dataclass, replace


@dataclass(frozen=True)
class Actor:
    running: bool = True
    epoch: int = 0
    readers: int = 0
    writer: int = -1
    data: int = 0
    version: int = 0


@dataclass(frozen=True)
class Turn:
    phase: str = "idle"
    epoch: int = -1


@dataclass(frozen=True)
class Model:
    actors: tuple[Actor, ...]
    turns: tuple[Turn, ...]
    audit: tuple[tuple[int, int, int], ...] = ()


def require(condition, message="sealed actor-state model check failed"):
    # Unlike 'assert', this check must survive python -O/-OO. This script
    # reports safety evidence; silently disabled checks would be a false pass.
    if not condition:
        raise AssertionError(message)


def put(xs, i, value):
    return xs[:i] + (value,) + xs[i + 1:]


def valid(m, owners, t):
    a = m.actors[owners[t]]
    return a.running and m.turns[t].epoch == a.epoch


def invariant(m, owners):
    if sum(a.version for a in m.actors) != len(m.audit):
        return False
    # The only authorized write toggles the value and increases version.
    # This catches bare-memory writes that avoid the audited write ledger.
    if any(a.data != (a.version & 1) for a in m.actors):
        return False
    if any(owners[t] != a or generation > m.actors[a].epoch
           for a, t, generation in m.audit):
        return False
    for ai, a in enumerate(m.actors):
        if a.readers and a.writer != -1:
            return False
        if a.writer != -1:
            t = a.writer
            if owners[t] != ai or m.turns[t].phase != "write" or not valid(m, owners, t):
                return False
        for t in range(len(owners)):
            if a.readers & (1 << t):
                if owners[t] != ai or m.turns[t].phase != "read" or not valid(m, owners, t):
                    return False
    for t, turn in enumerate(m.turns):
        a = m.actors[owners[t]]
        if turn.phase == "write" and a.writer != t:
            return False
        if turn.phase == "read" and not (a.readers & (1 << t)):
            return False
    return True


def successors(m, owners):
    for t, ai in enumerate(owners):
        a, turn = m.actors[ai], m.turns[t]
        if turn.phase == "idle" and a.running:
            yield (f"start t{t}", replace(m, turns=put(m.turns, t, Turn("active", a.epoch))))
        if turn.phase == "active" and valid(m, owners, t):
            if a.writer == -1:
                na = replace(a, readers=a.readers | (1 << t))
                yield (f"acquire-read t{t}", replace(m, actors=put(m.actors, ai, na),
                      turns=put(m.turns, t, Turn("read", turn.epoch))))
            if a.writer == -1 and a.readers == 0:
                na = replace(a, writer=t)
                yield (f"acquire-write t{t}", replace(m, actors=put(m.actors, ai, na),
                      turns=put(m.turns, t, Turn("write", turn.epoch))))
            yield (f"suspend t{t}", replace(m, turns=put(m.turns, t, Turn("paused", turn.epoch))))
        if turn.phase == "paused" and valid(m, owners, t):
            yield (f"resume t{t}", replace(m, turns=put(m.turns, t, Turn("active", turn.epoch))))
        if turn.phase == "read" and valid(m, owners, t):
            na = replace(a, readers=a.readers & ~(1 << t))
            yield (f"release-read t{t}", replace(m, actors=put(m.actors, ai, na),
                  turns=put(m.turns, t, Turn("active", turn.epoch))))
        if turn.phase == "write" and valid(m, owners, t):
            if a.version < 1:
                na = replace(a, data=1-a.data, version=a.version+1)
                yield (f"critical-write t{t}", replace(m, actors=put(m.actors, ai, na),
                      audit=m.audit + ((ai, t, a.epoch),)))
            na = replace(a, writer=-1)
            yield (f"release-write t{t}", replace(m, actors=put(m.actors, ai, na),
                  turns=put(m.turns, t, Turn("active", turn.epoch))))
        if turn.phase in ("active", "paused"):
            yield (f"end t{t}", replace(m, turns=put(m.turns, t, Turn("done", turn.epoch))))
    for ai, a in enumerate(m.actors):
        if a.running and a.epoch < 1:
            # STOP revokes every loan and cancels all active/suspended turns.
            turns = tuple(Turn("cancelled", t.epoch)
                          if owners[j] == ai and t.phase not in ("idle", "done", "cancelled")
                          else t for j, t in enumerate(m.turns))
            na = replace(a, running=False, epoch=a.epoch+1, readers=0, writer=-1)
            yield (f"stop actor{ai}", replace(m, actors=put(m.actors, ai, na), turns=turns))
        if not a.running:
            yield (f"restart actor{ai}", replace(m, actors=put(m.actors, ai, replace(a, running=True))))


def authenticated_write_transition(before, after, owners):
    """Verify the *edge*, not only the receipt totals in a state snapshot.

    An internally consistent ledger can still be forged without a live write
    admission. Any change to actor data/version must be an atomic write by the
    actor's current exclusive writer, recorded in the same transition.
    The runtime/compiler must separately prove this modeled gate is enforced.
    """
    if len(before.actors) != len(after.actors) or len(before.turns) != len(after.turns):
        return False
    changed = [i for i, (old, new) in enumerate(zip(before.actors, after.actors))
               if (old.data, old.version) != (new.data, new.version)]
    if not changed:
        return before.audit == after.audit
    if len(changed) != 1:
        return False
    ai = changed[0]
    actor = before.actors[ai]
    t = actor.writer
    if (t < 0 or t >= len(owners) or owners[t] != ai or actor.readers != 0
            or before.turns[t].phase != "write" or not valid(before, owners, t)
            or actor.version >= 1):
        return False
    return (
        after.actors == put(before.actors, ai,
                            replace(actor, data=1-actor.data, version=actor.version+1))
        and after.turns == before.turns
        and after.audit == before.audit + ((ai, t, actor.epoch),)
    )


def explore(owners):
    root = Model(tuple(Actor() for _ in range(max(owners)+1)), tuple(Turn() for _ in owners))
    queue = deque([root])
    seen = {root}
    edges = 0
    while queue:
        current = queue.popleft()
        require(invariant(current, owners), f"safety violation: {current!r}")
        for action, nxt in successors(current, owners):
            edges += 1
            require(invariant(nxt, owners), f"unsafe transition: {action} {current!r} -> {nxt!r}")
            require(authenticated_write_transition(current, nxt, owners),
                    f"unauthenticated mutation: {action} {current!r} -> {nxt!r}")
            if nxt not in seen:
                seen.add(nxt)
                queue.append(nxt)
    return len(seen), edges


def main():
    for owners in ((0,), (0, 1), (0, 0), (0, 0, 1), (0, 1, 0), (0, 1, 1), (0, 1, 2)):
        nodes, edges = explore(owners)
        print(f"PASS owners={owners}: {nodes} states, {edges} transitions")
    # Negative control: a write without its authenticated ledger entry.
    mutant = Model((Actor(data=1, version=1),), (Turn(),))
    require(not invariant(mutant, (0,)))
    bare_store = Model((Actor(data=1),), (Turn(),))
    require(not invariant(bare_store, (0,)))
    overlap = Model((Actor(readers=1, writer=0),), (Turn("write", 0),))
    require(not invariant(overlap, (0,)))
    # A forged receipt may satisfy the *snapshot* invariant. It must fail the
    # causal edge check because no write-capable continuation was admitted.
    root = Model((Actor(),), (Turn(),))
    coherent_forgery = Model((Actor(data=1, version=1),),
                             (Turn(),), ((0, 0, 0),))
    require(invariant(coherent_forgery, (0,)))
    require(not authenticated_write_transition(root, coherent_forgery, (0,)))
    # Even an audit append without changing data is not an authorized write.
    receipt_only = replace(root, audit=((0, 0, 0),))
    require(not authenticated_write_transition(root, receipt_only, (0,)))
    admitted = Model((Actor(writer=0),), (Turn("write", 0),))
    authorized = next(nxt for action, nxt in successors(admitted, (0,))
                      if action == "critical-write t0")
    require(authenticated_write_transition(admitted, authorized, (0,)))
    # No writer can retroactively authorize a raw write during an active read.
    reading = Model((Actor(readers=1),), (Turn("read", 0),))
    forged_during_read = replace(reading,
                                actors=(Actor(readers=1, data=1, version=1),),
                                audit=((0, 0, 0),))
    require(not authenticated_write_transition(reading, forged_during_read, (0,)))
    print("PASS raw store, forged receipt, and read/write overlap mutants rejected")


if __name__ == "__main__":
    main()

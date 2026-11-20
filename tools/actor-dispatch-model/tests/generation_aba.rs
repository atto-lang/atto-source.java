//! Bounded generation-reuse (ABA) model for actor dispatch tickets.
//!
//! This independently tests ticket identity when a finite queue slot is reused.
//! It is a Rust state-machine test, NOT JVM/Truffle guest-execution evidence.
//! No live actor, private source export or cross-backend parity is implied.
use std::collections::{HashMap, VecDeque};

const SLOTS: usize = 2;
const MAX_GENERATION: u8 = 2;
const WORKERS: u8 = 2;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct Ticket {
    slot: u8,
    generation: u8,
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct State {
    current: Option<Ticket>,
    worker: Option<u8>,
    running: Option<Ticket>,
    /// Highest epoch already issued in each reusable slot.
    issued: [u8; SLOTS],
    stopped: bool,
}

impl State {
    const fn initial() -> Self {
        Self {
            current: None,
            worker: None,
            running: None,
            issued: [0; SLOTS],
            stopped: false,
        }
    }
}

fn transitions(state: State, inject_aba: bool) -> Vec<(String, State)> {
    let mut result = Vec::new();

    if state.current.is_none() && !state.stopped {
        for slot in 0..SLOTS {
            if state.issued[slot] == MAX_GENERATION {
                continue;
            }
            let generation = state.issued[slot] + 1;
            let mut next = state;
            next.issued[slot] = generation;
            next.current = Some(Ticket {
                slot: slot as u8,
                generation,
            });
            result.push((format!("issue({slot},{generation})"), next));
        }
    }

    if let Some(ticket) = state.current {
        if state.worker.is_none() {
            if !state.stopped {
                for worker in 0..WORKERS {
                    result.push((
                        format!("enter({},{},{worker})", ticket.slot, ticket.generation),
                        State {
                            worker: Some(worker),
                            running: Some(ticket),
                            ..state
                        },
                    ));
                }
            }
            result.push((
                format!("cancel({},{})", ticket.slot, ticket.generation),
                State {
                    current: None,
                    ..state
                },
            ));
        } else if state.running == Some(ticket) {
            result.push((
                format!("exit({},{})", ticket.slot, ticket.generation),
                State {
                    current: None,
                    worker: None,
                    running: None,
                    ..state
                },
            ));
        }
    }

    if !state.stopped {
        result.push((
            "stop".to_string(),
            State {
                stopped: true,
                ..state
            },
        ));
    }

    // A queued completion can be replayed after its slot has been reused.
    // Identity MUST compare the generation as well as the numeric slot.
    for slot in 0..SLOTS {
        for generation in 1..=state.issued[slot] {
            let stale = Ticket {
                slot: slot as u8,
                generation,
            };
            if state.current == Some(stale) {
                continue; // A current completion isn't a stale replay.
            }
            let aba = state.current.is_some_and(|live| {
                live.slot == stale.slot && live.generation != stale.generation
            }) && state.worker.is_some();
            if inject_aba && aba {
                result.push((
                    format!("UNSAFE_ABA_release({slot},{generation})"),
                    State {
                        current: None,
                        ..state
                    },
                ));
            } else {
                result.push((format!("ignore_stale({slot},{generation})"), state));
            }
        }
    }

    result
}

fn violation(state: State) -> Option<&'static str> {
    if state.worker.is_some() != state.running.is_some() {
        return Some("worker/turn lease disagreement");
    }
    if state.worker.is_some() && state.current != state.running {
        return Some("stale generation revoked a live turn");
    }
    if let Some(ticket) = state.current {
        if ticket.slot as usize >= SLOTS
            || ticket.generation == 0
            || ticket.generation > state.issued[ticket.slot as usize]
        {
            return Some("active ticket was never issued");
        }
    }
    None
}

#[derive(Debug)]
struct Failure {
    reason: &'static str,
    path: Vec<String>,
}

fn explore(inject_aba: bool) -> (usize, Option<Failure>) {
    let initial = State::initial();
    let mut queue = VecDeque::from([initial]);
    let mut parents: HashMap<State, Option<(State, String)>> = HashMap::new();
    parents.insert(initial, None);

    while let Some(state) = queue.pop_front() {
        if let Some(reason) = violation(state) {
            let mut path = Vec::new();
            let mut cursor = state;
            while let Some((previous, operation)) = parents.get(&cursor).cloned().flatten() {
                path.push(operation);
                cursor = previous;
            }
            path.reverse();
            return (parents.len(), Some(Failure { reason, path }));
        }
        for (operation, next) in transitions(state, inject_aba) {
            if let std::collections::hash_map::Entry::Vacant(entry) = parents.entry(next) {
                entry.insert(Some((state, operation)));
                queue.push_back(next);
            }
        }
    }

    (parents.len(), None)
}

#[test]
fn all_bounded_reuses_preserve_generation_fenced_dispatch() {
    let (states, failure) = explore(false);
    assert!(states > 20, "model unexpectedly lost exploration coverage: {states}");
    assert!(failure.is_none(), "safe model violated authority: {failure:?}");
}

#[test]
fn same_slot_aba_mutation_has_concrete_witness() {
    let (states, failure) = explore(true);
    assert!(states > 20, "mutation model lost exploration coverage");
    let witness = failure.expect("a same-slot stale replay must corrupt an active newer turn");
    assert_eq!(witness.reason, "stale generation revoked a live turn");
    assert!(witness.path.iter().any(|p| p == "issue(0,1)" || p == "issue(1,1)"));
    assert!(witness.path.iter().any(|p| p == "issue(0,2)" || p == "issue(1,2)"));
    assert!(witness.path.iter().any(|p| p.starts_with("enter(")));
    assert!(witness
        .path
        .last()
        .is_some_and(|p| p.starts_with("UNSAFE_ABA_release(")));
}

#[test]
fn stale_completion_is_exactly_inert_even_during_reused_active_turn() {
    let live = Ticket { slot: 0, generation: 2 };
    let state = State {
        current: Some(live),
        worker: Some(1),
        running: Some(live),
        issued: [2, 0],
        stopped: false,
    };
    let safe = transitions(state, false);
    assert!(safe.iter().any(|(label, next)| {
        label == "ignore_stale(0,1)" && *next == state
    }));
    let mutating = transitions(state, true);
    assert!(mutating.iter().any(|(label, next)| {
        label == "UNSAFE_ABA_release(0,1)"
            && next.current.is_none()
            && next.running == Some(live)
            && violation(*next) == Some("stale generation revoked a live turn")
    }));
}

#[test]
fn stopping_blocks_new_epochs_but_preserves_release_of_running_turn() {
    let current = Ticket { slot: 1, generation: 2 };
    let stopped = State {
        current: Some(current),
        worker: Some(0),
        running: Some(current),
        issued: [1, 2],
        stopped: true,
    };
    let successors = transitions(stopped, false);
    assert!(successors.iter().all(|(event, _)| !event.starts_with("issue(")));
    assert!(successors.iter().any(|(event, next)| {
        event == "exit(1,2)" && next.current.is_none() && next.worker.is_none()
    }));
}

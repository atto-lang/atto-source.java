//! Bounded exhaustive check of actor dispatch-ticket authority.
//! A model checker, not a JVM/GraalVM execution proof.
use std::collections::{HashMap, VecDeque};

const FREE: i8 = -1;
const TICKET_COUNT: i8 = 3;
const WORKER_COUNT: i8 = 2;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct State {
    ticket: i8,
    worker: i8,
    running: i8,
    issued: u8,
    stopped: bool,
}

impl State {
    const fn initial() -> Self {
        Self {
            ticket: FREE,
            worker: FREE,
            running: FREE,
            issued: 0,
            stopped: false,
        }
    }
}

#[derive(Debug)]
struct Failure {
    reason: &'static str,
    path: Vec<String>,
}

fn transitions(s: State, unsafe_replay: bool) -> Vec<(String, State)> {
    let mut out = Vec::new();

    if !s.stopped && s.ticket == FREE {
        for ticket in 0..TICKET_COUNT {
            let bit = 1_u8 << ticket;
            if s.issued & bit == 0 {
                out.push((
                    format!("issue({ticket})"),
                    State {
                        ticket,
                        issued: s.issued | bit,
                        ..s
                    },
                ));
            }
        }
    }

    if s.ticket != FREE && s.worker == FREE && !s.stopped {
        for worker in 0..WORKER_COUNT {
            out.push((
                format!("enter({},{worker})", s.ticket),
                State {
                    worker,
                    running: s.ticket,
                    ..s
                },
            ));
        }
    }

    if s.worker != FREE && s.running == s.ticket {
        out.push((
            format!("exit({})", s.ticket),
            State {
                ticket: FREE,
                worker: FREE,
                running: FREE,
                ..s
            },
        ));
    }

    if s.ticket != FREE && s.worker == FREE {
        out.push((
            format!("cancel_queued({})", s.ticket),
            State { ticket: FREE, ..s },
        ));
    }

    if !s.stopped {
        out.push(("stop".to_string(), State { stopped: true, ..s }));
    }

    for ticket in 0..TICKET_COUNT {
        if s.issued & (1_u8 << ticket) != 0 && ticket != s.ticket {
            if unsafe_replay && s.ticket != FREE {
                out.push((
                    format!("UNSAFE_stale_release({ticket})"),
                    State { ticket: FREE, ..s },
                ));
            } else {
                out.push((format!("ignore_stale({ticket})"), s));
            }
        }
    }

    out
}

fn violation(s: State) -> Option<&'static str> {
    if (s.worker == FREE) != (s.running == FREE) {
        return Some("physical worker and running-ticket disagree");
    }
    if s.worker != FREE && (s.ticket == FREE || s.running != s.ticket) {
        return Some("obsolete ticket released an active newer dispatch");
    }
    if s.ticket != FREE && s.issued & (1_u8 << s.ticket) == 0 {
        return Some("unissued ticket became active");
    }
    None
}

fn explore(unsafe_replay: bool) -> (usize, Option<Failure>) {
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

        for (operation, next) in transitions(state, unsafe_replay) {
            if let std::collections::hash_map::Entry::Vacant(entry) = parents.entry(next) {
                entry.insert(Some((state, operation)));
                queue.push_back(next);
            }
        }
    }

    (parents.len(), None)
}

fn main() {
    let (safe_states, safe_failure) = explore(false);
    assert!(safe_failure.is_none(), "safe dispatch model failed: {safe_failure:?}");
    assert_eq!(safe_states, 88, "reference state-space count changed");

    let (unsafe_states, unsafe_failure) = explore(true);
    let failure = unsafe_failure.expect("unsafe stale release must yield a counterexample");
    assert_eq!(unsafe_states, 84, "reference mutation state-space count changed");
    assert_eq!(failure.reason, "obsolete ticket released an active newer dispatch");
    assert_eq!(
        failure.path.last().map(String::as_str),
        Some("UNSAFE_stale_release(0)")
    );

    println!("PASS: {safe_states} dispatch-ticket states, no stale lease release");
    println!(
        "MUTATION DETECTED after {unsafe_states} states: {}",
        failure.path.join(" -> ")
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_model_preserves_dispatch_authority() {
        let (count, failure) = explore(false);
        assert_eq!(count, 88);
        assert!(failure.is_none());
    }

    #[test]
    fn unsafe_replay_has_minimal_reproducible_witness() {
        let (count, failure) = explore(true);
        assert_eq!(count, 84);
        let failure = failure.expect("stale replay mutation must be caught");
        assert_eq!(failure.reason, "obsolete ticket released an active newer dispatch");
        assert_eq!(
            failure.path,
            [
                "issue(0)",
                "cancel_queued(0)",
                "issue(1)",
                "enter(1,0)",
                "UNSAFE_stale_release(0)",
            ]
        );
    }

    #[test]
    fn ignored_stale_event_preserves_exact_state() {
        let current = State {
            ticket: 1,
            worker: 0,
            running: 1,
            issued: 0b11,
            stopped: false,
        };
        let stale = transitions(current, false);
        assert!(stale
            .iter()
            .any(|(event, next)| event == "ignore_stale(0)" && *next == current));
        assert!(violation(current).is_none());
    }

    #[test]
    fn violation_rejects_physical_or_authority_corruption() {
        let mut bad = State::initial();
        bad.worker = 0;
        assert_eq!(
            violation(bad),
            Some("physical worker and running-ticket disagree")
        );

        bad.running = 1;
        assert_eq!(
            violation(bad),
            Some("obsolete ticket released an active newer dispatch")
        );

        bad.worker = FREE;
        bad.running = FREE;
        bad.ticket = 2;
        assert_eq!(violation(bad), Some("unissued ticket became active"));
    }

    #[test]
    fn stop_cannot_issue_a_new_ticket() {
        let stopped = State {
            stopped: true,
            ..State::initial()
        };
        assert!(transitions(stopped, false)
            .iter()
            .all(|(event, _)| !event.starts_with("issue(")));
    }
}

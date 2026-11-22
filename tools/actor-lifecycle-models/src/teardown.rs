//! Bounded actor stop/teardown state machine, including pre-fix scheduling mutation.
//! Translation of scripts/check_actor_teardown_state_machine.py.
use std::collections::{HashSet, VecDeque};

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct State {
    stopped: bool,
    pending: bool,
    scheduled: bool,
    carrier: bool,
    guest_returned: bool,
    revoked: bool,
    finalized: bool,
    tear_down_count: u8,
}

impl State {
    const fn initial() -> Self {
        Self {
            stopped: false,
            pending: false,
            scheduled: true,
            carrier: false,
            guest_returned: false,
            revoked: false,
            finalized: false,
            tear_down_count: 0,
        }
    }
}

#[derive(Debug)]
struct Report {
    visited: usize,
    stranded: Option<Vec<&'static str>>,
}

fn transitions(s: State, retain_stopped_teardown_wake: bool) -> Vec<(&'static str, State)> {
    if s.finalized {
        return Vec::new();
    }

    let mut out = Vec::new();

    if !s.stopped {
        out.push((
            "stop",
            State {
                stopped: true,
                pending: true,
                scheduled: s.scheduled || !s.carrier,
                ..s
            },
        ));
    }

    if s.stopped && !s.revoked {
        out.push((
            "cancel",
            State {
                revoked: true,
                pending: false,
                ..s
            },
        ));
    }

    if s.scheduled && !s.carrier {
        out.push((
            "carrier-enter",
            State {
                carrier: true,
                guest_returned: false,
                ..s
            },
        ));
    }

    if s.carrier && !s.guest_returned {
        let run_teardown = s.pending && s.stopped && !s.revoked;
        out.push((
            "guest-return",
            State {
                guest_returned: true,
                pending: if run_teardown { false } else { s.pending },
                tear_down_count: s.tear_down_count + u8::from(run_teardown),
                ..s
            },
        ));
    }

    if s.carrier && s.guest_returned {
        let keep = retain_stopped_teardown_wake && s.pending && s.stopped && !s.revoked;
        out.push((
            "carrier-exit",
            State {
                carrier: false,
                scheduled: if s.stopped { keep } else { false },
                finalized: s.stopped && !s.pending,
                ..s
            },
        ));
    }

    if s.stopped && !s.scheduled && !s.carrier && !s.pending {
        out.push((
            "finalize",
            State {
                finalized: true,
                ..s
            },
        ));
    }

    out
}

fn explore(retain_stopped_teardown_wake: bool) -> Report {
    let initial = State::initial();
    let mut queue = VecDeque::from([(initial, Vec::<&'static str>::new())]);
    let mut visited = HashSet::from([initial]);

    while let Some((state, trace)) = queue.pop_front() {
        assert!(
            state.tear_down_count <= 1,
            "teardown executed more than once: {trace:?}, {state:?}"
        );
        assert!(
            !(state.finalized && state.pending),
            "finalization retained pending teardown: {trace:?}, {state:?}"
        );

        if state.stopped
            && state.pending
            && !state.scheduled
            && !state.carrier
            && !state.finalized
        {
            return Report {
                visited: visited.len(),
                stranded: Some(trace),
            };
        }

        for (action, next) in transitions(state, retain_stopped_teardown_wake) {
            if visited.insert(next) {
                let mut next_trace = trace.clone();
                next_trace.push(action);
                queue.push_back((next, next_trace));
            }
        }
    }

    Report {
        visited: visited.len(),
        stranded: None,
    }
}

pub fn verify() {
    let old = explore(false);
    assert_eq!(old.visited, 17, "pre-fix baseline changed");
    let trace = old
        .stranded
        .expect("unsafe scheduling must strand a pending teardown");
    assert_eq!(
        trace.join(" -> "),
        "carrier-enter -> guest-return -> stop -> carrier-exit"
    );

    let fixed = explore(true);
    assert_eq!(fixed.visited, 17, "corrected baseline changed");
    assert!(fixed.stranded.is_none(), "corrected teardown stranded");

    println!("PASS: corrected stop/teardown model explored 17 states");
    println!(
        "MUTATION DETECTED: old scheduling stranded teardown after {}",
        trace.join(" -> ")
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn corrected_teardown_never_strands_pending_work() {
        let result = explore(true);
        assert_eq!(result.visited, 17);
        assert!(result.stranded.is_none());
    }

    #[test]
    fn old_scheduler_produces_expected_counterexample() {
        let result = explore(false);
        assert_eq!(result.visited, 17);
        assert_eq!(
            result.stranded.expect("old scheduler must fail").join(" -> "),
            "carrier-enter -> guest-return -> stop -> carrier-exit"
        );
    }

    #[test]
    fn finalization_is_terminal() {
        let state = State {
            finalized: true,
            ..State::initial()
        };
        assert!(transitions(state, true).is_empty());
    }

    #[test]
    fn revoked_stop_cannot_run_teardown() {
        let state = State {
            stopped: true,
            pending: true,
            carrier: true,
            revoked: true,
            ..State::initial()
        };
        let next = transitions(state, true)
            .into_iter()
            .find(|(event, _)| *event == "guest-return")
            .expect("carrier return must be modeled")
            .1;
        assert_eq!(next.tear_down_count, 0);
        assert!(next.pending);
    }

    #[test]
    fn physically_active_turn_preserves_pending_wake_on_stop() {
        let state = State {
            carrier: true,
            scheduled: true,
            ..State::initial()
        };
        let next = transitions(state, true)
            .into_iter()
            .find(|(event, _)| *event == "stop")
            .expect("stop must be enabled")
            .1;
        assert!(next.pending);
        assert!(next.scheduled);
    }
}

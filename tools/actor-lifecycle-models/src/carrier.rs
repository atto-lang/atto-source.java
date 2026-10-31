//! Exhaustive, bounded per-actor physical carrier lease model.
//! Translation of scripts/verify_actor_carrier_exclusivity.py; not a runtime proof.
use std::collections::{HashMap, VecDeque};

const FREE: i8 = -1;
const READY: i8 = 0;
const RUNNING: i8 = 1;
const SUSPENDED: i8 = 2;
const DONE: i8 = 3;
const JOB_ACTOR: [usize; 3] = [0, 0, 1];
const ACTORS: usize = 2;
const WORKERS: usize = 2;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct State {
    lease: [i8; ACTORS],
    carriers: [i8; WORKERS],
    jobs: [i8; 3],
    stopped: [bool; ACTORS],
}

impl State {
    const fn initial() -> Self {
        Self {
            lease: [FREE; ACTORS],
            carriers: [FREE; WORKERS],
            jobs: [READY; 3],
            stopped: [false; ACTORS],
        }
    }
}

#[derive(Debug)]
struct Report {
    count: usize,
    violation: Option<(&'static str, Vec<String>)>,
    witness: Option<Vec<String>>,
}

fn transitions(s: State, unsafe_double_claim: bool) -> Vec<(String, State)> {
    let mut out = Vec::new();
    for (job, status) in s.jobs.iter().copied().enumerate() {
        let actor = JOB_ACTOR[job];

        if status == READY && !s.stopped[actor] {
            for (worker, occupied) in s.carriers.iter().copied().enumerate() {
                if occupied == FREE && (s.lease[actor] == FREE || unsafe_double_claim) {
                    let mut next = s;
                    next.lease[actor] = worker as i8;
                    next.carriers[worker] = job as i8;
                    next.jobs[job] = RUNNING;
                    out.push((
                        format!("claim actor={actor} job={job} worker={worker}"),
                        next,
                    ));
                }
            }
        }

        if status == RUNNING {
            for (worker, current) in s.carriers.iter().copied().enumerate() {
                if current == job as i8 {
                    for (outcome, status) in [("await", SUSPENDED), ("complete", DONE)] {
                        let mut next = s;
                        next.lease[actor] = FREE;
                        next.carriers[worker] = FREE;
                        next.jobs[job] = status;
                        out.push((
                            format!("{outcome} actor={actor} job={job} worker={worker}"),
                            next,
                        ));
                    }
                }
            }
        }

        if status == SUSPENDED && !s.stopped[actor] {
            let mut next = s;
            next.jobs[job] = READY;
            out.push((format!("wake actor={actor} job={job}"), next));
        }

        if status == READY || status == SUSPENDED {
            let mut next = s;
            next.jobs[job] = DONE;
            out.push((format!("cancel actor={actor} job={job}"), next));
        }
    }

    for (actor, stopped) in s.stopped.iter().copied().enumerate() {
        if !stopped {
            let mut next = s;
            next.stopped[actor] = true;
            out.push((format!("stop actor={actor}"), next));
        }
    }
    out
}

fn violation(s: State) -> Option<&'static str> {
    // Derive physical ownership from workers, independent of the advertised lease.
    let mut executing: [Vec<usize>; ACTORS] = std::array::from_fn(|_| Vec::new());
    for (worker, job) in s.carriers.iter().copied().enumerate() {
        if job != FREE {
            let Some(&actor) = JOB_ACTOR.get(job as usize) else {
                return Some("invalid physical carrier job identity");
            };
            executing[actor].push(worker);
        }
    }

    if executing.iter().any(|workers| workers.len() > 1) {
        return Some("two physical carriers executing same actor");
    }

    for (actor, lease) in s.lease.iter().copied().enumerate() {
        if (lease == FREE) != executing[actor].is_empty() {
            return Some("lease disagrees with physical executor");
        }
        if executing[actor]
            .first()
            .is_some_and(|&worker| lease != worker as i8)
        {
            return Some("execution owner disagrees with lease");
        }
    }

    for (job, status) in s.jobs.iter().copied().enumerate() {
        if (status == RUNNING) != s.carriers.contains(&(job as i8)) {
            return Some("task run state disagrees with carrier occupancy");
        }
    }
    None
}

fn trace(mut at: State, parents: &HashMap<State, Option<(State, String)>>) -> Vec<String> {
    let mut path = Vec::new();
    while let Some((previous, operation)) = parents.get(&at).cloned().flatten() {
        path.push(operation);
        at = previous;
    }
    path.reverse();
    path
}

fn explore(unsafe_double_claim: bool) -> Report {
    let initial = State::initial();
    let mut queue = VecDeque::from([initial]);
    let mut parents = HashMap::from([(initial, None::<(State, String)>)]);
    let mut witness = None;

    while let Some(s) = queue.pop_front() {
        if let Some(reason) = violation(s) {
            return Report {
                count: parents.len(),
                violation: Some((reason, trace(s, &parents))),
                witness,
            };
        }

        // Reentrant actor 0: job 0 suspended while job 1 owns a worker.
        if witness.is_none() && s.jobs[0] == SUSPENDED && s.jobs[1] == RUNNING {
            witness = Some(trace(s, &parents));
        }

        for (action, next) in transitions(s, unsafe_double_claim) {
            if let std::collections::hash_map::Entry::Vacant(entry) = parents.entry(next) {
                entry.insert(Some((s, action)));
                queue.push_back(next);
            }
        }
    }

    Report {
        count: parents.len(),
        violation: None,
        witness,
    }
}

pub fn verify() {
    let safe = explore(false);
    assert!(safe.violation.is_none(), "safe carrier model failed: {safe:?}");
    assert_eq!(safe.count, 372, "reference safe state count changed");
    let witness = safe.witness.expect("reentrant interleaving must be reachable");
    assert_eq!(
        witness.join(" -> "),
        "claim actor=0 job=0 worker=0 -> await actor=0 job=0 worker=0 -> claim actor=0 job=1 worker=0"
    );

    let unsafe_report = explore(true);
    assert_eq!(unsafe_report.count, 65);
    let (reason, path) = unsafe_report
        .violation
        .expect("double-carrier mutation must be detected");
    assert_eq!(reason, "two physical carriers executing same actor");
    assert_eq!(
        path.join(" -> "),
        "claim actor=0 job=0 worker=0 -> claim actor=0 job=1 worker=1"
    );
    println!("PASS: 372 actor-carrier states, physical actor exclusivity intact");
    println!("MUTATION DETECTED: {reason}; {}", path.join(" -> "));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_state_space_and_reentrancy_witness() {
        let result = explore(false);
        assert_eq!(result.count, 372);
        assert!(result.violation.is_none());
        assert!(result.witness.is_some());
    }

    #[test]
    fn mutant_produces_minimal_double_carrier_counterexample() {
        let result = explore(true);
        assert_eq!(result.count, 65);
        let (reason, trace) = result.violation.expect("mutation must be detected");
        assert_eq!(reason, "two physical carriers executing same actor");
        assert_eq!(
            trace.join(" -> "),
            "claim actor=0 job=0 worker=0 -> claim actor=0 job=1 worker=1"
        );
    }

    #[test]
    fn malformed_lease_and_run_state_fail_closed() {
        let mut invalid = State::initial();
        invalid.lease[0] = 0;
        assert_eq!(
            violation(invalid),
            Some("lease disagrees with physical executor")
        );

        invalid = State::initial();
        invalid.carriers[0] = 0;
        assert_eq!(
            violation(invalid),
            Some("lease disagrees with physical executor")
        );

        invalid = State::initial();
        invalid.carriers[0] = 0;
        invalid.lease[0] = 1;
        assert_eq!(
            violation(invalid),
            Some("execution owner disagrees with lease")
        );

        invalid = State::initial();
        invalid.jobs[0] = RUNNING;
        assert_eq!(
            violation(invalid),
            Some("task run state disagrees with carrier occupancy")
        );
    }

    #[test]
    fn malformed_physical_job_identity_fails_closed() {
        let mut invalid = State::initial();
        invalid.carriers[0] = 99;
        assert_eq!(
            violation(invalid),
            Some("invalid physical carrier job identity")
        );

        invalid.carriers[0] = -2;
        assert_eq!(
            violation(invalid),
            Some("invalid physical carrier job identity")
        );
    }

    #[test]
    fn stopped_actor_cannot_admit_new_jobs() {
        let initial = State {
            stopped: [true, false],
            ..State::initial()
        };
        assert!(transitions(initial, false)
            .iter()
            .all(|(action, _)| !action.starts_with("claim actor=0")));
    }
}

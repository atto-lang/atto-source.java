//! A deterministic model for actor request/message admission.
//!
//! NOT the production Oreslang runtime. This models the target scheduler
//! invariants for requestConcurrency/messageConcurrency > 1 without ever
//! allowing two simultaneous actor execution turns.

use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    pub requests: usize,
    pub messages_per_request: usize,
}

impl Limits {
    pub fn new(requests: usize, messages_per_request: usize) -> Result<Self, Error> {
        if requests == 0 || messages_per_request == 0
            || requests > 1024 || messages_per_request > 1024
            || requests.saturating_mul(messages_per_request) > 4096
        {
            return Err(Error::InvalidLimit);
        }
        Ok(Self { requests, messages_per_request })
    }

    pub fn serial() -> Self { Self { requests: 1, messages_per_request: 1 } }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    InvalidLimit,
    RequestLimit,
    MessageLimit,
    DuplicateRequest,
    DuplicateMessage,
    UnknownRequest,
    UnknownMessage,
    RequestNotEmpty,
    TurnBusy,
    NotRunnable,
    WrongLease,
    TurnStillActive,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase { Ready, Running, Suspended }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Lease {
    request: u64,
    message: u64,
    token: u64,
}

#[derive(Default)]
struct Request {
    messages: BTreeMap<u64, Phase>,
}

pub struct Admission {
    limits: Limits,
    requests: BTreeMap<u64, Request>,
    active: Option<Lease>,
    next_token: u64,
}

impl Admission {
    pub fn new(limits: Limits) -> Self {
        Self { limits, requests: BTreeMap::new(), active: None, next_token: 0 }
    }

    pub fn active_lease(&self) -> Option<Lease> { self.active }
    pub fn request_count(&self) -> usize { self.requests.len() }

    pub fn admit_request(&mut self, id: u64) -> Result<(), Error> {
        if self.requests.contains_key(&id) { return Err(Error::DuplicateRequest); }
        if self.requests.len() >= self.limits.requests { return Err(Error::RequestLimit); }
        self.requests.insert(id, Request::default());
        Ok(())
    }

    pub fn admit_message(&mut self, request: u64, message: u64) -> Result<(), Error> {
        let r = self.requests.get_mut(&request).ok_or(Error::UnknownRequest)?;
        if r.messages.contains_key(&message) { return Err(Error::DuplicateMessage); }
        if r.messages.len() >= self.limits.messages_per_request {
            return Err(Error::MessageLimit);
        }
        r.messages.insert(message, Phase::Ready);
        Ok(())
    }

    pub fn enter(&mut self, request: u64, message: u64) -> Result<Lease, Error> {
        if self.active.is_some() { return Err(Error::TurnBusy); }
        let phase = self.requests.get_mut(&request)
            .ok_or(Error::UnknownRequest)?.messages.get_mut(&message)
            .ok_or(Error::UnknownMessage)?;
        if *phase != Phase::Ready { return Err(Error::NotRunnable); }
        let token = self.next_token.checked_add(1).ok_or(Error::InvalidLimit)?;
        self.next_token = token;
        let lease = Lease { request, message, token };
        *phase = Phase::Running;
        self.active = Some(lease);
        Ok(lease)
    }

    /// Suspension ends only this *execution turn*. Its message lifetime
    /// remains admitted, and the scheduler can admit a different ready turn.
    pub fn suspend(&mut self, lease: Lease) -> Result<(), Error> {
        self.transition(lease, Phase::Suspended)
    }

    /// A turn may complete without ending its containing message lifetime,
    /// e.g. a cooperative non-suspending yield.
    pub fn yield_turn(&mut self, lease: Lease) -> Result<(), Error> {
        self.transition(lease, Phase::Ready)
    }

    fn transition(&mut self, lease: Lease, to: Phase) -> Result<(), Error> {
        if self.active != Some(lease) { return Err(Error::WrongLease); }
        let current = self.requests.get_mut(&lease.request)
            .ok_or(Error::UnknownRequest)?.messages.get_mut(&lease.message)
            .ok_or(Error::UnknownMessage)?;
        if *current != Phase::Running { return Err(Error::NotRunnable); }
        *current = to;
        self.active = None;
        Ok(())
    }

    pub fn wake(&mut self, request: u64, message: u64) -> Result<(), Error> {
        let phase = self.requests.get_mut(&request)
            .ok_or(Error::UnknownRequest)?.messages.get_mut(&message)
            .ok_or(Error::UnknownMessage)?;
        if *phase != Phase::Suspended { return Err(Error::NotRunnable); }
        *phase = Phase::Ready;
        Ok(())
    }

    pub fn finish_message(&mut self, request: u64, message: u64) -> Result<(), Error> {
        if self.active.is_some_and(|lease|
            lease.request == request && lease.message == message) {
            return Err(Error::TurnStillActive);
        }
        let r = self.requests.get_mut(&request).ok_or(Error::UnknownRequest)?;
        if r.messages.remove(&message).is_none() { return Err(Error::UnknownMessage); }
        Ok(())
    }

    pub fn finish_request(&mut self, request: u64) -> Result<(), Error> {
        let r = self.requests.get(&request).ok_or(Error::UnknownRequest)?;
        if !r.messages.is_empty() { return Err(Error::RequestNotEmpty); }
        self.requests.remove(&request);
        Ok(())
    }

    /// A bounded state invariant, suitable for refinement testing against
    /// the eventual source actor implementation.
    pub fn check_invariants(&self) -> bool {
        if self.requests.len() > self.limits.requests { return false; }
        let mut running = 0;
        for r in self.requests.values() {
            if r.messages.len() > self.limits.messages_per_request { return false; }
            running += r.messages.values().filter(|x| **x == Phase::Running).count();
        }
        if running > 1 || running != usize::from(self.active.is_some()) { return false; }
        if let Some(lease) = self.active {
            self.requests.get(&lease.request)
                .and_then(|r| r.messages.get(&lease.message))
                == Some(&Phase::Running)
        } else { true }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serial_is_a_logical_critical_section_across_await() {
        let mut a = Admission::new(Limits::serial());
        a.admit_request(1).unwrap();
        a.admit_message(1, 10).unwrap();
        let lease = a.enter(1, 10).unwrap();
        a.suspend(lease).unwrap();
        assert_eq!(a.admit_request(2), Err(Error::RequestLimit));
        assert_eq!(a.admit_message(1, 11), Err(Error::MessageLimit));
        assert_eq!(a.enter(1, 10), Err(Error::NotRunnable));
        a.wake(1, 10).unwrap();
        let resumed = a.enter(1, 10).unwrap();
        assert_ne!(lease, resumed);
        a.yield_turn(resumed).unwrap();
        a.finish_message(1, 10).unwrap();
        a.finish_request(1).unwrap();
        a.admit_request(2).unwrap();
        assert!(a.check_invariants());
    }

    #[test]
    fn three_requests_with_four_messages_each_never_create_parallel_native_turns() {
        let mut a = Admission::new(Limits::new(3, 4).unwrap());
        for r in 0..3 {
            a.admit_request(r).unwrap();
            for m in 0..4 { a.admit_message(r, m).unwrap(); }
        }
        assert_eq!(a.admit_request(3), Err(Error::RequestLimit));
        assert_eq!(a.admit_message(0, 4), Err(Error::MessageLimit));
        let one = a.enter(0, 0).unwrap();
        assert_eq!(a.enter(2, 3), Err(Error::TurnBusy));
        assert!(a.check_invariants());
        a.suspend(one).unwrap();
        let two = a.enter(2, 3).unwrap();
        assert!(a.check_invariants());
        assert_eq!(a.enter(1, 1), Err(Error::TurnBusy));
        a.yield_turn(two).unwrap();
        a.wake(0, 0).unwrap();
        let resumed = a.enter(0, 0).unwrap();
        a.yield_turn(resumed).unwrap();
        assert!(a.check_invariants());
    }

    #[test]
    fn duplicate_requests_messages_and_early_request_close_fail() {
        let mut a = Admission::new(Limits::new(3, 4).unwrap());
        a.admit_request(42).unwrap();
        assert_eq!(a.admit_request(42), Err(Error::DuplicateRequest));
        a.admit_message(42, 1).unwrap();
        assert_eq!(a.admit_message(42, 1), Err(Error::DuplicateMessage));
        assert_eq!(a.finish_request(42), Err(Error::RequestNotEmpty));
        let lease = a.enter(42, 1).unwrap();
        assert_eq!(a.finish_message(42, 1), Err(Error::TurnStillActive));
        a.yield_turn(lease).unwrap();
        a.finish_message(42, 1).unwrap();
        a.finish_request(42).unwrap();
    }

    #[test]
    fn stale_lease_cannot_end_another_message_turn() {
        let mut a = Admission::new(Limits::new(2, 2).unwrap());
        a.admit_request(1).unwrap();
        a.admit_message(1, 1).unwrap();
        a.admit_message(1, 2).unwrap();
        let stale = a.enter(1, 1).unwrap();
        a.yield_turn(stale).unwrap();
        let current = a.enter(1, 2).unwrap();
        assert_eq!(a.suspend(stale), Err(Error::WrongLease));
        assert_eq!(a.active_lease(), Some(current));
        assert!(a.check_invariants());
        a.suspend(current).unwrap();
    }

    #[test]
    fn invalid_limits_are_rejected() {
        assert_eq!(Limits::new(0, 1), Err(Error::InvalidLimit));
        assert_eq!(Limits::new(3, 0), Err(Error::InvalidLimit));
        assert_eq!(Limits::new(1025, 1), Err(Error::InvalidLimit));
        assert_eq!(Limits::new(100, 100), Err(Error::InvalidLimit));
        assert_eq!(Limits::new(3, 4), Ok(Limits { requests: 3, messages_per_request: 4 }));
    }
}

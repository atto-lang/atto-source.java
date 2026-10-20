# OTP pg + ETS source slice v1

Status: executable Oreslang policy/protocol source stacked on the existing
six-module OTP foundation. This document does **not** claim full OTP behavior
parity or a completed source-to-runtime bridge.

## Why these are OTP modules

Oreslang keeps the useful OTP ideas:

- `pg`: named/scoped actor discovery and membership, with actor-death cleanup;
- ETS: efficient shared runtime-owned indexed data without serializing every
  lookup through one server actor.

It deliberately rejects legacy ambiguity:

- `pg` is not consensus, a lock, supervision authority or exactly-once
  broadcast;
- duplicate joins are intended to be idempotent rather than hidden
  reference-counted memberships;
- distributed membership must state its consistency policy explicitly;
- ETS tables are generic/typed at the Oreslang API boundary;
- table storage is a separate runtime memory domain, never an alias into an
  actor's private mutable arena;
- match-spec is not copied as a second dynamic language. Typed Oreslang
  patterns/predicates should lower to bounded backend query plans;
- ambient VM-global table/group naming is not authority. Names/scopes are
  resolved through explicit runtime capabilities and registries.

## What this source slice provides

`otp/pg` currently provides:

- typed descriptive `Member<TargetRef>` metadata with positive incarnation
  validation;
- bounded membership-capacity preflight;
- independently bounded named-group-capacity preflight.

Constructing a `Member` value does **not** join a group and its incarnation
integer is not an authentication token. The runtime remains authoritative.

`otp/ets` currently provides:

- bounded table cardinality preflight;
- SET replacement-vs-insert capacity semantics;
- bounded query/snapshot-limit validation.

It does **not** emulate table storage in guest collections or expose forgeable
numeric table IDs.

## Runtime integration

Java/Graal runtime substrate work is tracked separately in PR #1700. That
runtime slice provides an ActorGroup-backed local process-group registry and a
bounded runtime-owned SET table with explicit access policy and atomic point
operations.

This source PR intentionally does not reach into those Java classes. The next
bridge must be compiler/runtime-owned so guest code receives typed opaque
handles/capabilities rather than host implementation objects.

## Still required for parity

- typed source-visible `ProcessGroup<Message>` and scoped membership handles;
- source-visible `Table<K,V>` handles with private/protected/public access;
- compiler lowering to runtime primitives on Java/Graal and LLVM;
- ordered-set, bag and duplicate-bag storage;
- atomic counters/update operations and bounded typed query plans;
- explicit owner transfer/heir semantics if retained;
- distributed pg provider with documented eventual membership convergence;
- differential Java/LLVM tests and actual exact-head guest execution evidence.

Until those gates pass, the module inventory may be eight while behavioral
parity remains intentionally incomplete.

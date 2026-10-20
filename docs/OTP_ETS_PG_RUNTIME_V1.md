# OTP pg + ETS runtime substrate v1

Status: Java/Graal host-runtime implementation slice. This is **not** source-level
`otp/pg` / `otp/ets` completion and does not claim LLVM or distributed parity.

## pg

`ProcessGroupRegistry` is intentionally a thin named/scope facade over
`ActorRuntime.ActorGroup`. There is one membership engine, one bounded
membership quota, and one actor-retirement cleanup path.

This slice guarantees:

- named groups are runtime-owned rather than owned by the first actor that joins;
- `(scope, name)` cardinality is explicitly bounded;
- duplicate ActorGroup admission is idempotent;
- explicit leave reclaims an empty name immediately;
- actor retirement removes membership, and the next registry operation reclaims
  an empty name;
- deleting/closing a pg group does not cancel ordinary members;
- member enumeration resolves only live runtime-local ActorRefs;
- non-members cannot use member enumeration to bypass ActorGroup observation
  authority;
- pg remains local-only here. A future distributed provider must advertise
  eventual membership semantics explicitly and cannot pretend to be consensus.

There is deliberately no pg-specific broadcast transport. Callers obtain typed
members and send over ordinary ActorRef/mailbox transport, preserving mailbox
capacity, isolation and delivery semantics.

## ETS

`EtsTable<K,V>` is the first bounded runtime-owned SET substrate.

This slice guarantees:

- explicit capacity and PRIVATE / PROTECTED / PUBLIC access policy;
- deep-freeze admission through the existing runtime data-only boundary;
- mutable host objects and live capabilities cannot be retained as shared
  mutable table values;
- point operations are serialized by a table-local lock: get, put/replace,
  insert-if-absent, remove and value compare-and-set;
- owner-created tables close automatically when the owner actor retires;
- all tables close and release retained runtime memory during ActorRuntime
  shutdown;
- private/untrusted readers receive isolated immutable copies instead of a
  retained table-domain object graph;
- cross-ActorRuntime access from actor execution fails closed.

The table is not stored in an actor arena. Retained keys/values are charged
against the runtime shared-memory accounting used by the existing frozen event
payload path.

## Deliberately still pending

- source-level `stdlib/otp/pg.ores` and `stdlib/otp/ets.ores` APIs/resolver;
- compile-time generic typing/lowering from Oreslang source;
- ordered-set, bag and duplicate-bag backends;
- atomic counters and richer bounded query/update plans;
- distributed pg membership provider;
- LLVM/native substrates and differential tests.

Until those gates land, documentation and common-source manifests must continue
to mark the source modules pending rather than advertising full OTP parity.

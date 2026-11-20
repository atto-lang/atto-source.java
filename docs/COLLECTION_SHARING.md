# Collection ownership and sharing

Ordinary mutable collections require no internal synchronization while access
is exclusive. Ownership can move to another execution domain when the element
types are Send. Shared references require a separate Sync proof; allocating a
collection or marking a view readonly does not provide that proof.

| Value | Send | Sync / Share |
| --- | --- | --- |
| Ordinary mutable collection | When elements are Send | No implicit proof |
| Readonly record view | When fields are Send | No implicit proof |
| SharedMutex owning either value | When payload is Send | Provided by mutex protection |
| Arc of a readonly record view | No derived proof | No derived proof |

Readonly describes permitted access through a view. It does not freeze the
backing allocation or revoke writable aliases. TypeAutoTraits,
ThreadSafetyTraits, and the independent ownership Task classifier must agree
on this distinction. ScopedTaskReadAuthority must reject readonly record
referents until a separate frozen-allocation/exclusivity witness exists.

Protected references and guards remain scoped. A lock protects a compound
operation only when the whole operation executes under that protection.
Separate contains/get and insert/set calls do not establish one atomic update.

Run `bash scripts/test_collection_sharing.sh` with JDK 21 or newer. The
dependency-free probe compiles the production frontend and checks trait
composition, scoped READ admission, ordinary collection mutation, explicit
mutex protection, use after transfer, unsafe sharing, and guard suspension.
JUnit coverage also lives in TypeAutoTraitsTest and ScopedTaskReadAuthorityV2Test.

This patch hardens Java compiler admission. It does not introduce a frozen
collection type, an ETS table runtime, or prove other backends conform.

## Ownership boundary regressions

The test matrix includes a writable record assignable to a readonly record
view. Type compatibility permits that narrowing, but it does **not** consume
or freeze the original writable allocation. Neither the readonly view nor an
`Option`, `Result`, `Arc`, or `Weak` wrapper may infer `Sync` or `Share`
from the narrowing. A borrowed readonly alias must also fail the separate
`ThreadSafetyTraits` cross-thread `Send` query.

The frontend probe tests ordinary local readonly reads, denies `rt share`
of a move-only readonly record, rejects borrowed mutable struct capture by
an independent Task, and checks that `SharedMutex.new` takes its owned
collection rather than leaving a second unprotected owner. It also rejects
constructing mutex state from a borrowed value. The JUnit test exercises
`ScopedTaskReadAuthority.prove` against an already-published checked Task
epoch; an invalid referent must fail even with valid Task admission.

These are complementary boundaries. `Send` of an owned record does not
authorize a borrowed reference or a retained writable alias to cross lanes.
`SharedMutex` only justifies sharing **through** its protected interface.


A dedicated source fixture also passes a mutable `infer struct` into a
`structural {value: int}` readonly parameter, reads synchronously, and
continues mutating the caller-owned struct. Negative companions attempt
to capture that readonly parameter in an independent Task or publish
it via `rt share`. The intended failure is the ownership boundary,
not parser rejection.

## Scoped Task READ requires two independent proofs

The general `TypeAutoTraits` table models possible Send/Sync/Share
capabilities, including synchronization supplied by an owning
`SharedMutex<T>` handle. That **does not** establish an audited borrowed
reference lifetime or executable cross-thread `Sync` proof. The scoped
Task READ authority additionally requires `ThreadSafetyTraits.SYNC`
to pass with no nominal override. This denies general-Sync wrappers
(`SharedMutex`, `ActorRef`, `Arc`, `Weak`, `Box`, and compositions)
while their runtime borrowing/identity proof is unresolved. Immutable
scalar tuples still pass. This does not remove owned synchronized handles
or prevent legal `SharedMutex` operations; it prevents using a broader
trait result to forge the narrower unguarded scoped READ exception.

The bounded `ThreadSafetyTraits` validation runs **before** generic
`TypeAutoTraits` recursion. It rejects malformed or adversarially deep
referent metadata (256-depth, 100,000-step limits), rather than allowing
recursive capability derivation to exhaust the compiler first. JUnit and
the frontend probe both include a 4,096-level nested `Option` negative;
these latest cases remain unexecuted on the unmodified Java toolchain.

The extra gate is deliberately fail-closed. Lifting it for a wrapper
requires a verified runtime lowering, allocation/lifetime and lane
proof, not only changing the static classifier.

## Exact-head merge verification

Run the **unmodified** branch source with JDK 21 or newer:

```sh
bash scripts/test_collection_sharing.sh
mvn -B -ntp -Dores.runtime.carriers=native verify
```

Keep the PR in draft until source-level fixtures, JUnit tests, and existing
examples execute on the exact head and record JVM version, test totals, job
steps, and commit/tree identity. A GitHub Actions failure with no assigned
runner and no executed steps is infrastructure-admission evidence, **not** a
compiler or Maven test failure. Private cross-organization mirroring remains
forbidden unless the independently reviewed destination policy allows it.

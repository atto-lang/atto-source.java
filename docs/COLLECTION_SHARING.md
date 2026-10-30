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

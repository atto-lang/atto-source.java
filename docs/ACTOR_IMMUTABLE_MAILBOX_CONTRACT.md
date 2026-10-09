# Actor immutable mailbox contract

Shared actors execute concurrently in the same JVM address space, as Akka/Pekko actors do.
Oreslang must enforce immutable message transport in core rather than relying on Scala-style conventions.

1. One guest turn per actor, enforced by scheduling gate and physical carrier ownership.
2. Safe publication occurs via concurrent mailbox synchronization, locks and atomic/volatile operations.
   JVM happens-before guarantees visibility; there is no explicit CPU-cache flushing requirement.
3. Plain List/Set/Map/array messages are deep-frozen before SHARED actor delivery.
   Host opaque objects, cycles and executable closures are rejected.
4. Published Shared<T> deeply immutable snapshots may be sent to several shared actors
   with identical graph identity; they avoid copies after trusted publication.
5. Isolated actors copy transport payloads into independent memory domains.
6. rt copy allocates independent storage for supported classes/arrays/lists/records/Option/Result.
   It refuses actors, host capabilities, cycles and unknown data graphs.
7. rt share of move-only mutable values remains forbidden until frozen type projection is implemented.

No userland mutex is required for actor-owned state. Deliberately shared mutable
state must go through core-managed synchronized state owners or explicit capabilities.
Executed exact-head Java tests are required before marking these rules proven.
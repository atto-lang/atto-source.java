# Fifty ownership/suspension source programs

`manifest.tsv` has one row per independent `.ores` source file:
filename, verdict, diagnostic substring or expected-output regex.

- 34 negative fixtures must reach semantic checking and reject with the stated
  diagnostic; a parse failure does not count as ownership evidence.
- 14 positive fixtures execute on Graal with bounded loops/channels/actor shutdown
  and assert output. Fairness fixture 47 must observe automatic loop cooperation;
  mutex fixture 49 must observe zero loop cooperations while its guard is live.
- Two positive fixtures only assert compilation: nonblocking registration with an
  uncaptured lexical loan, and an untrusted loop that charges quota/checkpoints
  without an automatic carrier handoff.

Covered combinations: shared/exclusive borrows, direct/qualified/static/instance
`*_blocking` calls, later argument suspension, async and synchronous generators,
`for await` and ordinary generator iteration, static/dynamic/default/immediate/
nonblocking select, read/write channels, cooperation, trusted/shared/private/
untrusted actors, loop budgeting, and mutex no-yield regions.

Run the dynamic JUnit suite:

```
mvn -Dores.runtime.carriers=native -Dtest=OwnershipSuspensionMatrixTest test
```

For repeated fresh-context scheduling runs (14 executed programs per repetition):

```
mvn -Dores.runtime.carriers=native -Dtest=OwnershipSuspensionMatrixTest \
    -Dores.ownership.matrix.repetitions=5 test
```

The fixtures target the Java current-main audit branch. They do not claim native
LLVM admission or serial/critical feature parity. Companion feature tests cover
critical state loans. Runtime reduction/quota accounting checks are distinct from
resumable handoff: a checkpoint alone does not release an actor carrier.

This is regression evidence, not a proof that the whole language is data-race-free.
Unknown imported/host/FFI effects, general ownership/drop parity, and pending
copy/shared-handle implementations retain their separate admission/release gates.
Logical race freedom needs protocol invariants in addition to borrow checking.

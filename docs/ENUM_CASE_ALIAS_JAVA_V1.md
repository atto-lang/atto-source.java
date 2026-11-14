# Canonical enum patterns and scoped case loans — Java/Graal V1

**Status:** IMPLEMENTATION DRAFT / SOURCE-COMPILER TESTS AUTHORED, NOT EXECUTED.
**Specification:** `attolang-language-spec.md#60`.
**Stack:** Java `oreslang-source.java#3098` on nominal enum #907 and match-expression #901. Not yet reconciled with current `main`.

## Compiler changes

- Canonical `.atto` enum declarations `define enum WorkerMsg { Run { job_id: int, payload: Bytes }, Stop }` preserve nominal case identity and locally deterministic tags (NOT stable wire tags). Historical tagged syntax remains supported.
- Bare `on Run` and qualified `on WorkerMsg.Run` resolve only against the typed scrutinee; duplicate, foreign, unknown and nonexhaustive matches fail before guest execution.
- The AST retains named field names, independent whole-case aliases, and legacy positional patterns. Field renaming `job_id as j` is deferred.
- Copy-only cases use readonly field bindings and case-local narrowed aliases without an extra alias object.
- **Non-Copy case loan admission:** `on Run { job_id, payload } as r` and `on Run as r` admit a source-name match subject with `payload: Bytes`, but represent `r` and every non-Copy named field as immutable borrows rooted in that subject. Copy scalar fields remain values. The TypeChecker projects `r.job_id` by its case-refined static shape and `r.payload` as `&Bytes`, not owned Bytes.
- The OwnershipChecker issues persistent scoped immutable borrow leases for each non-Copy case view/field; the arm's scope releases them on exit. Attempts to move the message while the loan is active, `rt take` a borrowed projection, return a borrow from the arm, or cross `rt cooperate` / another suspension barrier are rejected. Borrowed matches require a named subject for provenance; matching a temporary owned value through this borrow path is not silently admitted.
- `match` expressions cannot export a borrowed value beyond the arm's lifetime; `do match` statement arms may return independent Copy results.
- The guest evaluator binds `r` to the same authenticated enum value and projects readonly payload field values without an alias-specific clone or implicit serialization.

## Test surfaces

```sh
mvn -B -ntp -Dtest=CanonicalEnumDeclarationGuestTest,BareEnumPatternGuestTest,NullaryEnumCaseAliasGuestTest,NamedEnumCaseFieldsGuestTest,BorrowedEnumCaseLoanTest -DfailIfNoTests=true test
```

The checked-in `.atto` fixtures test Copy-case guest dispatch and non-Copy case ownership at the compiler type/borrow-checker level, including negative moves, suspension and return escape. `.github/workflows/enum-case-alias-java-v1.yml` uses exact-head read-only checkout and retains Surefire reports.

**Evidence limit:** no runner has executed these new tests. Parser/type/ownership acceptance and JUnit guest execution are therefore **not verified**, and neither GraalVM Native Image AOT nor actor-turn concurrency tests have been completed. Source-content/static-shape inspection is NOT compiler execution.

## Unsupported and unresolved

- Borrow lifetime across nested asynchronous actor receive turns, mailbox transfers and `ActorRef<WorkerMsg>` remains unproven. No claim of full actor-heap or thread safety is made.
- Raw bytes network codecs, versioned wire schema, supervisor CONTROL notices, imported schema identity and multi-backend parity are separate gates.
- Borrowed non-Copy case-field methods must not regain mutable access or extend the loan; compiler and runtime enforcement require executed positive/negative tests and independent security review.
- No new per-arm heap allocation is requested by the AST, but there is **no measured zero-allocation guarantee** and existing Java/Graal runtime allocation remains outside this PR's proof.
- Reconcile stacked branch with newer mainline enum/actor work before merging. Do not transport private source to a different org solely for Actions minutes without independently approved snapshot and destination.

Keep PR #3098 **draft** until exact-head compiler tests run with stepful receipts, memory/actor safety review is complete, and source-tree provenance is verified.

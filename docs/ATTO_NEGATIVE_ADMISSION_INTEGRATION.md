# AttoLang negative compiler-admission integration candidate

Status: **DRAFT / source only / guest execution NOT_EXECUTED** (2026-11-03).

This branch is based on the exact current Java `.atto` entrypoint/import
candidate (PR #2459) and ports only two narrow diagnostic contracts from
the independently authored critical/ownership candidate (PR #2422).
It deliberately does **not** merge that branch's actor gate, scheduler,
critical AST/ownership proof, physical ticket, or runtime guest changes.
Those branches have diverged materially; their full semantic reconciliation
requires an actual source-authorized compiler test runner and independent
review. `.ores` source compatibility and current imports remain intact.

## Supported source admission only

| Native LRU negative fixture / scenario | Diagnostic on this Java branch | Scope |
| --- | --- | --- |
| forbidden `critical suspends` declaration | `E_CRITICAL_SUSPENDS_DECL` | Parser declines conflicting callable modifiers; no runtime authorization |
| `rt share` of move-only, mutable cache | `E_SHARE_MOVE_ONLY` | General ownership checker declines implicit mutable sharing; no Shared<T> lowering |
| `critical-read` mutates LRU recency | NOT_IMPLEMENTED | Reader/writer protected-state effects, guard authority, and typed callee proofs absent |
| sealed state/borrow capability escapes | NOT_IMPLEMENTED | Requires typed sealed-state ownership and verified protection boundary |

These are diagnostic **contracts**, not conformance results. Importing the
native LRU negative fixtures into Java is intentionally not done: the LRU
repository remains private and no independent source-transfer authorization
exists. The Java tests use independently authored minimal scalar/array
source examples. They must never claim to execute the real LRU implementation.

The stable `E_CRITICAL_SUSPENDS_DECL` is emitted before lowering a forbidden
callable. A plain `critical fnc` still fails closed and does not gain
scheduler-owned ticket authority. The `E_SHARE_MOVE_ONLY` check applies to
all non-Copy values and preserves `rt share` on copyable immutable scalars.

## Execution contract

The source-owned workflow
`.github/workflows/atto-semantic-negative-admission.yml` must execute
on the **exact PR head Git tree**, with nonzero recorded runner steps.
It compiles the Java source and requires three nonempty zero-failure JUnit
Surefire suites:

- `AttoNegativeDiagnosticsAdmissionTest` (negative parser/ownership admission, plus a real `.atto` copyable scalar-share guest-output `42` candidate)
- `AttoSourceAdmissionTest` (extension imports and linked `.atto` guest)
- `AttoCacheShapeAdmissionTest` (tiny linked integer-column/cache-like `.atto` guest)

The latter two classes and the new positive scalar-share method contain actual guest compile/execute test candidates,
but their successful **hosted execution has NOT yet been established**.
A JUnit source file or GitHub workflow definition is not evidence.
Only nonzero job steps, unskipped Surefire XML and actual guest outputs count.

Run on a source-authorized JDK 21 Java runner:

```sh
mvn -B -ntp -DfailIfNoTests=true -DskipTests=false -Dmaven.test.skip=false \
  -Dtest=AttoNegativeDiagnosticsAdmissionTest,AttoSourceAdmissionTest,AttoCacheShapeAdmissionTest \
  clean test
```

This source-hosted run does not compile or execute the ten pinned private
LRU guest fixtures, does not prove GraalVM Native Image AOT, and does
not establish LLVM, Wasm, .NET NativeAOT or Cranelift parity.

## Non-goals and release holds

No `async` source grammar migration is smuggled into this scoped change.
Legacy `.ores` behavior is not silently rewritten by a speculative
extension-sensitive parse mode. No protected read/write effect is treated as
a mere string token. The other two negatives must be implemented as
compiler AST/type/ownership rules and actual runtime invariants—not
fixture text pattern matches or generic parser errors.

Open upstream blocks:
- Source-org GitHub Actions runner admission issue #963.
- Independent private-source transfer/security authorization issue #962.
- Full critical/suspends compiler/actor runtime PR #2422.
- Native `.atto` source entry/import PR #2459.
- LRU source/host reference PRs #1 and #2, separate private organization.

Keep all integration and compiler PRs draft; independent approval and real
execution receipts are required before a merge to `main`.

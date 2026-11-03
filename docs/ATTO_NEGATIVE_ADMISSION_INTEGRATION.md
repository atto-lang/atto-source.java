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
| `critical-read` assigns protected scalar field | `E_CRITICAL_READ_MUTATION` | **Opt-in single-i64 AST-to-MIR only**; read lease never calls a writer |
| `critical-read` calls mutating `IntLruCache.get()` | NOT_IMPLEMENTED | General class-method effect inference / protected object heaps absent |
| protected region alias returned or `rt borrow/share/take/copy` returned | `E_STATE_BORROW_ESCAPE` | **Opt-in single-i64 scoped guard only**; alias cannot become a value |
| `CriticalIntLruCache.state` escapes outside its guard | NOT_IMPLEMENTED | General sealed-state ownership, nominal module imports and external borrow lifetime absent |

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
It compiles the Java source and requires four nonempty zero-failure JUnit
Surefire suites:

- `AttoNegativeDiagnosticsAdmissionTest` (negative parser/ownership admission, plus a real `.atto` copyable scalar-share guest-output `42` candidate)
- `AttoSourceAdmissionTest` (extension imports and linked `.atto` guest)
- `AttoCacheShapeAdmissionTest` (tiny linked integer-column/cache-like `.atto` guest)
- `ProtectedScalarMirV1Test` (dedicated parser AST -> scalar protected MIR, read-only lease, false-write and lexical alias escape rejection, including three independently authored `.atto` fixture files)

The latter two classes and the new positive scalar-share method contain actual guest compile/execute test candidates,
but their successful **hosted execution has NOT yet been established**.
A JUnit source file or GitHub workflow definition is not evidence.
Only nonzero job steps, unskipped Surefire XML and actual guest outputs count.

Run on a source-authorized JDK 21 Java runner:

```sh
mvn -B -ntp -DfailIfNoTests=true -DskipTests=false -Dmaven.test.skip=false \
  -Dtest=AttoNegativeDiagnosticsAdmissionTest,AttoSourceAdmissionTest,AttoCacheShapeAdmissionTest,ProtectedScalarMirV1Test \
  clean test
```

This source-hosted run does not compile or execute the ten pinned private
LRU guest fixtures, does not prove GraalVM Native Image AOT, and does
not establish LLVM, Wasm, .NET NativeAOT or Cranelift parity.

## Non-goals and release holds

No `async` source grammar migration is smuggled into this scoped change.
Legacy `.ores` behavior is not silently rewritten by a speculative
extension-sensitive parse mode. The new protected read/write slice uses
production Parser-owned `Ast.StateContainerDecl` and a bounded, closed
`Ast.ProtectedMethod.bodyTokens()` to construct an exact scalar MIR
instruction sequence; it is deliberately **not** general source-expression,
method-effect or ownership inference.

The `STATE_READ` plan contains only `ACQUIRE_READ -> LOAD_STATE_I64 ->
RELEASE_READ -> RETURN_I64`, and its VM implementation calls
`ScalarStateContainer.tryRead()` without giving guest code a reference or
a callback. Attempts to write under READ or return the protected alias
fail with their distinct diagnostic codes. The existing WRITE plan retains
checked addition, overflow rollback and lexical permit release. Unknown
methods, secondary object fields, FFI, generics, unproven borrowed data
and virtual-actor read replies still fail closed.

**Missing for actual LRU:** typed `IntLruCache.get(mut self)` effects must
propagate through `handle_state(critical-read |s| -> s.cache.get(key))`,
and external `c.state` must be rejected using nominal protected-state
visibility and borrow-lifetime evidence. Neither full fixture has a
compiler-specific positive/negative execution receipt. No substring or
generic parse failure can substitute for those two semantic proofs.

Open upstream blocks:
- Source-org GitHub Actions runner admission issue #963.
- Independent private-source transfer/security authorization issue #962.
- Full critical/suspends compiler/actor runtime PR #2422.
- Native `.atto` source entry/import PR #2459.
- LRU source/host reference PRs #1 and #2, separate private organization.

Keep all integration and compiler PRs draft; independent approval and real
execution receipts are required before a merge to `main`.


## Source-owned scalar read negative fixtures (2026-11-03)

- `examples/attolang/protected-read-scalar.atto` must compile via the opt-in
  `OresCompiler.compileProtectedScalarI64` entrypoint, use a real READ lease,
  return 41, and preserve state. It must **not** pass the general compiler
  type-checking path while generalized protected lowering is disabled.
- `examples/attolang/negative-read-mutation.atto` must emit
  `E_CRITICAL_READ_MUTATION` under that same scalar entrypoint.
- `examples/attolang/negative-region-alias-escape.atto` must emit
  `E_STATE_BORROW_ESCAPE` under that same scalar entrypoint.

JUnit also probes direct alias returns, read-guard `rt borrow/share/take/copy`
returns, unsupported call/read targets, and rejection of a forged READ plan
containing `STORE_STATE_I64`. These are **authored** negative tests, not
executed test passes, until source-owned CI admits a runner and produces
nonempty Surefire case receipts.

This scoped acceptance does not alter the private LRU fixture hashes or
authorize importing its source into another repository or organization.

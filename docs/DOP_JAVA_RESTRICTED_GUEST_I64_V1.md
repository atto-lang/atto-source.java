# Restricted AttoLang inverted i64 guest lane (Java v1)

`OresCompiler.compileInvertedI64Guest(source)` compiles a **closed,
opt-in language subset** using the production Lexer into immutable, checked
operations and executes them against the existing `InvertedColumnStore`
physical SoA primitive. This is the narrow first source-to-storage path;
it is **not** general `Parser`/`TypeChecker` admission, Graal native-image,
an actor, a JIT/AOT optimizer, or cross-backend parity.

## Accepted source shape

- One nominal `define inverted struct Row as ... end` declaration.
- Exactly two distinct scalar `int` columns, optional `let` mutability
  per column; no generics, methods, inheritance, nested layouts or classes.
- One `fnc main(): int`, no args, no suspension, one owner-local
  `InvertedArray<Row>.with_capacity(N)`, `0 <= N <= 4096`.
- Literal-only `push`, `erase_swap`, `set` on `let` columns and a
  terminal `return rows.get(denseIndex, column)`. No casts, reflection,
  plugin calls, functions, callbacks, variable aliases or channel traffic.
- A bounded 10,000 operation count and 262,144 character input limit.

All other tokens fail closed. The ordinary `OresCompiler.parseAndTypeCheck`
API **still rejects** inverted schemas and unlowered generic owners.
This is intentional until production AST/HIR/MIR and guest execution
share one admissible checked lowering path.

## Semantics and limitations

The checked plan is immutable and cannot be forged through its constructor;
each execution creates and closes an owner-private byte-backed column store.
Its `Handle` values never become guest-visible; dense indices are resolved
to live handles immediately before operations. A removed dense index produces
`BoundsTrap`, not an arbitrary host exception. Exceeding declared capacity
produces `CapacityTrap`, without implicit store growth. Source `let`
authorizes a column set in this restricted lane; bare columns cannot be set.

The three canonical `.atto` fixtures under `contracts/dop-inverted-v1`
match the LLVM and conformance Git blobs. The swap/reuse and immutable-column-swap positive traces are expected
to return **42L**, while the negative trace is expected to throw the
language-lane `BoundsTrap`. This does not claim that Java throws LLVM's native
SIGILL/SIGTRAP: the two backends intentionally report target-specific failure
forms under the same source-visible bounds contract.

## Qualification

The source was compiled and manually exercised under JDK 21 against
**synthetic test stubs** for the parser/runtime API (5 checks); this only
validates the narrow Java source's syntax/control structure. It does not
replace executed JUnit tests against the real production Lexer and
`InvertedColumnStore`.

Release gate: source-local exact-head seven-case Java JUnit execution with real runtime,
independent review, no unsupported fallback, plus complete compiler AST,
ownership MIR and Graal/AOT support before any general guest-parity claim.
Never qualify a workflow reporting `steps=[]` or `steps=null`.

## Source-org CI admission hardening

The dedicated workflow now runs the pinned source/fixture verifier before
compilation and **again after** the seven-case guest JUnit suite. The final
verifier is streamed from `HEAD:tools/verify-dop-inverted-atto-contract.sh`
rather than from the mutable working tree. Both stages require the immutable
PR head, untouched tracked source, and matching Git-tree **and** on-disk
SHA-1 blob IDs for all three canonical `.atto` fixtures.

The workflow's path filter covers the production lexer, token definitions,
parser, nominal AST, TypeChecker, OwnershipChecker, runtime store, compiler
entry points, Maven build, JUnit report checker and source-verification scripts.
The no-Python policy is preserved: the gate uses Bash, Git and a hardened
JDK XML parser. A throwaway-Git-repository mutation harness exercises one
valid case plus five fail-closed changes (worktree, index, checker, symlink,
head revision), without altering the actual checked-out source tree.

These gates are **authored, not yet CI-executed** while source-org runner
admission is blocked. A passing checksum alone cannot attest that Maven,
GraalVM or an independent exact-head guest actually ran.

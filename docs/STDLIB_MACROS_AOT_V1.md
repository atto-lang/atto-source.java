# Compiler-owned AttoLang macro library — Java/Graal AOT stage 0

**Status:** authored source and JUnit gates on a stacked macro-integration branch.
Compiler execution, complete JUnit and GraalVM Native Image guest execution are **NOT_VERIFIED**.

## Public compiler-only API

- `stdlib/core/macros.atto`: `const_int!(const <closed int expression>)` and `splice_expr!(syntax expr <expression>)`.
- `stdlib/std/macros.atto`: `const_retry_count!(const <closed int expression>)`, `const_timeout_ns!(const <closed int expression>)`.
- `stdlib/alloc/macros.atto`: `const_capacity!(const <closed int expression>)`, `const_alignment!(const <closed int expression>)`.

Each file is owned by this compiler. These are **not** host-language implementations or shared
runtime objects. The canonical declaration form is `define pub macro`; ordinary invocations
use `name!(const ...)` or `name!(syntax expr ...)`. Import aliases and package ownership
must be resolved by the compiler's checked macro linker before library-qualified use:
mere existence of these source files does not imply automatic stdlib registration.

Each accepted invocation produces an ordinary typed AST expression during compilation.
No runtime macro evaluator, source parser, raw token substitution, network, clock, FFI,
actor capability, arbitrary host file access, or runtime fallback is permitted. Expansion
precedes type/effect/ownership/capability checks and finite AOT closure. The scalar
intersection budget is 8 declarations, 128 expansions, 512 const AST visits and
inclusive depth 64 per relevant compilation profile.

**Important:** `const_capacity`, `const_alignment`, `const_timeout_ns` and
`const_retry_count` guarantee only **compile-time closure**. They do NOT prove
non-negativity, power-of-two alignment, sensible retry count, valid duration,
allocator quotas or caller capability. Downstream runtime APIs must still validate.

## Compiler-backed gate

`StdlibMacroAotSourceTest` reads all three **actual** compiler-owned `.atto` files,
expands seven positive paths to a literal `42`, asserts erasure of executable macro AST
nodes, and rejects runtime constants, mismatched syntax kinds and signed-i64 overflow.
It tests a single translation unit: it does **not** certify imported macro linking,
full native guest AOT, or other backends.

Gates before shipping: execute exact-head JDK25/JUnit suite; prove actual program-specific
GraalVM Native Image build and guest execution; verify no macro runtime symbols or
source parser in the binary; test qualified cross-file import with module provenance;
review import hygiene, cycle limits, deterministic keys and source export policy.
Keep macro integration PR #1398 and this dependent PR unmerged until material evidence.

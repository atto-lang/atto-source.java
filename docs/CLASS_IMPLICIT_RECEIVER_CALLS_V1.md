# Class and actor implicit receiver calls — draft v1 contract

Status: Java source implementation on a draft branch. **Not verified for release or backend parity.**

## Decision

Inside an instance method, a bare direct invocation `roodles(a)` resolves as
`self.roodles(a)` **only** when there is no lexical binding called `roodles`
and the receiver's class or ancestors declare that *instance method family*.
Instance-method families win over same-named file/module functions. A wrong
arity or inaccessible method produces the ordinary method error; it MUST NOT
fall back to a global function. Lexically bound callable locals/parameters
always win. Static class functions, file-level callables and constructors do
not acquire an implicit receiver.

This is a **direct-call convenience only**. Unqualified field reads/writes are
not introduced. Names used as values never bind `self`. In particular:

```ores
define class Foo as
  pub roodles(): void { return; }

  pub park_it(): void {
    self.roodles();              // direct invocation; valid
    roodles();                   // direct invocation; same receiver and method slot

    val r = self.roodles;        // ERROR: no implicit bound method value
    val s = roodles;             // NOT an implicit bound method value
  }
end
```

A method value that intentionally captures a receiver requires an explicit
lambda with permitted lexical capture, such as `lex[self] || -> self.roodles()`
where the capture/borrow checker admits the receiver and its lifetime.
`lex[self]` only permits *lexical visibility*, not a move, send, lifetime
extension, actor-state access or callback escape. An ordinary lambda has no
implicit `self` capture. Actor `self` remains a non-escaping mailbox
capability. Nested critical state loans must never be retained by a closure.

## Explicit file-root qualifier: `pkg::name`

The canonical shadowing escape hatch is `pkg::roodles(...)`. It selects a
callable declared at the current source file's root, even if a local callable
or instance method has the same name. It does **not** search the project,
other files, imported symbols, named modules, or the runtime class hierarchy.

```ores
fnc roodles(): int { return 99; }
define module helpers as
  pub fnc roodles(): int { return 33; }
end
define class Foo as
  pub roodles(): int { return 7; }
  pub run(): int {
    val pkg = 5;            // unrelated lexical name
    return roodles() + pkg::roodles() + helpers::roodles();
  }
end
```

* `roodles()` resolves the class method (7), `pkg::roodles()` the current
  file's top-level function (99), and `helpers::roodles()` the module (33).
* `pkg.roodles()` remains normal member access, including when the user
  defines a module named `pkg`. Only `pkg::` is compiler syntax.
* `pkg::` is not a runtime object, and the parser emits an unforgeable
  identifier marker which cannot be spelled by source identifiers.
* Explicit lookup is exact: absent name, arity mismatch, unsupported member
  kind and ambiguous function values are hard errors, never fallback.
* `pkg::fnc_name` is a callable value only when the file-root function is
  legitimately reifiable. Routines, generic/overloaded and suspending
  callables remain direct-call-only as appropriate.
* Private file-root functions are accessible to code in the same file.
  Actor code MUST NOT use `pkg::` to escape the existing same-file
  dependency isolation rule: compiler and runtime independently reject that.
* `pkg::` never implicitly binds `self`, moves class fields, grants
  import/export privileges, or creates a cross-file/package namespace.

This feature currently has Java source changes and test cases, but **not**
exact-head JVM or Native Image execution proof. Compiler-generic AST
transformations and all other backends still require verification.

## Explicit import qualifier: `imports::name`

`imports::roodles(...)` selects an **explicitly named `import fnc`
binding** of the current compilation unit. It is orthogonal to
`pkg::roodles(...)` (file-root) and `roodles(...)` (lexical/method/root
resolution). This first version does **not** flatten wildcard imports, search
dependencies transitively, treat an imported class/module/type as a callable,
or implicitly import external routines.

```ores
import fnc roodles from "./lib.ores";

fnc roodles(): int { return 99; }

define class Foo as
  pub roodles(): int { return 7; }
  pub run(): void {
    stdio.stdout.write(roodles());           // class method
    stdio.stdout.write(pkg::roodles());      // this file's function
    stdio.stdout.write(imports::roodles());  // explicitly imported function
  }
end
```

The import's **locally bound name** is used: `import fnc roodles as remote
from "...";` is addressed with `imports::remote()`, never
`imports::roodles()`. File-root `fnc` imports may share a name with a
file-root `fnc`/routine; unqualified `roodles()` binds to the local
declaration (with no wrong-arity fallback), and `imports::roodles()` binds
to the import. Builtin names, non-function local declarations, and duplicate
import aliases still conflict as before.

`imports::` uses a separate unforgeable AST namespace marker, not a runtime
value, object property, or reflection lookup. `imports.roodles` retains
normal member semantics even if a local binding called `imports` exists.
`imports::name` is **direct-call-only** in this version:
`imports::name(...)` is supported, but `val f = imports::name` is
rejected until an explicit linked parameter/return/lifetime/effect
signature is available. Otherwise a later indirect `f(...)` call could
bypass the borrow checking performed on `imports::name(...)`. Source imports
still use their linker's exact source path and public export checks, and
Java host `fnc` imports still require their original host capability
and allowlist. No private export or implicit actor same-file dependency
is authorized. Imported function signatures remain opaque to the
per-code-unit typechecker unless independently linked; this is **not**
proof of end-to-end type or effect safety. Opaque `imports::` calls reject
borrowed argument values, borrow-backed projections, live lexical borrows
and live MutexGuard values
rather than silently exporting unknown lifetimes. The linker and runtime
reject `import fnc` of a suspending declaration: the current first-class
function type cannot safely represent its suspension effect. Ordinary
owned arguments still pass through the type and ownership analyses.
Proof of linked parameter/return types, scheduler effects and backend parity
remains pending; no ABI correctness claim is made by this draft.



**Unchecked-AST runtime fence (Java draft):** The synchronous evaluator and
suspendable expression evaluator both reject a bare `imports::name` member
value, even when parser/type/ownership checking was bypassed by generated or
forged AST. Only the separate `imports::name(...)` invocation path may
retrieve a verified import target. Rejecting value extraction before creating
an `Invokable` prevents opaque parameter, effect, suspension or borrow
signatures from being erased by indirect calls. This source change is
**not executed proof**; test the exact branch head in the JVM and native
profiles before promotion.

## Receiver binding and performance

Each class/actor code image holds shared method implementations and immutable
dispatch slots. Instances hold their own state and class identity, not cloned
bound callbacks. Invocation passes one receiver into the called method frame,
where `self` has its checked receiver-polymorphic type. No bound-method
allocation is required for `roodles()` or `self.roodles()`.

`method(self, T x)` and `method(T x)` define exactly the same method slot:
the receiver does not contribute to caller-visible arity. A mutable receiver
requires a valid exclusive borrow. All inheritance, visibility, overrides,
effect annotations, suspension rules, method overload selection and actor
isolation remain identical between the two call spellings.

Constructor self is incompletely initialized, so an implicit method call from
the constructor must fail exactly as an explicit `self.method()` call fails
until transitive constructor-safe effect summaries exist.

## Implementation gates

1. Parser accepts the two direct-call spellings without rewriting field names.
2. Type checker resolves the same method family, lexical shadowing, overload
   arity, inheritance and visibility for both spellings.
3. Ownership checker must resolve the same method **before** checking loans,
   suspension, mutation, actor state and constructor restrictions.
4. Runtime must never manufacture an instance-bound callback on ordinary
   class member extraction; direct instance calls dispatch through the shared
   method table. Static function values remain a separate concept.
5. Test nested calls, generic/async methods, late-bound overrides, overload
   arity mismatch, local function shadowing, actor self, no implicit closure
   capture, and constructor partial-self rejection.
6. Verify exact Git tree SHA through JVM unit/guest tests and GraalVM native
   image, then replicate with real compiler evidence on LLVM, .NET, Forge and
   Wasm. A source-only patch or zero-step Actions run is not a passing test.
7. Private CI copies are forbidden unless the exact destination repository,
   numeric ID and reviewed carrier contract have been approved independently.

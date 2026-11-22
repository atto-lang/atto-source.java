# Qualified direct-call scopes — current-main integration

Status: **DRAFT / NOT EXECUTED**. This branch is stacked on Java #3137, not directly on the old Java #2155 source tree.

## Resolution contract

Inside a real class/actor instance method:

1. Lexical value bindings win, including callable parameters; noncallable shadows fail closed.
2. The complete compiler-resolved lexical instance-method family wins by **name**, before selecting arity. Bare `work(...)` is checked as `self.work(...)`.
3. Ordinary free/function fallback is allowed only when the current lexical class has no such family.
4. Compiler and standard intrinsics remain reserved before the implicit receiver lookup.

`pkg::work(...)` is resolved to **the current compilation unit's exact file-root** `__root__.work` callable. It is not a package scan or module method; actor source-unit dependency restrictions are preserved. `pkg.work(...)` remains an ordinary member expression.

`imports::work(...)` resolves **only** an explicitly named or aliased `import fnc` binding; a wildcard import or user value named `imports` cannot mint it. A host function still requires its normal interop capability; an unexported imported function cannot bypass the linker. Calls whose types/effects cannot be completely linked are conservatively treated as opaque and cannot transmit borrowed values/MutexGuards or cross active lexical loans.

The parser uses special `$pkg_root$` and `$imports$` AST identities. Those cannot be written as source identifiers. Both scopes are currently **direct-call-only**; first-class imported and file-root callable extraction is rejected in this integration profile until a typed ownership/effect/lifetime signature is linked. That restriction is deliberately more conservative than older #2155 experiments that permitted a qualified `pkg::fnc` value.

A file-root name overlapping an explicit import still selects the file-root family for bare `name(...)`; wrong arity is an error, **not** a fallback to the imported function. Use `imports::name(...)` explicitly.

## Sealed actor state boundary

These qualifiers do not manufacture actor state loans. Bare `handle_state(critical...)` must eventually resolve to the compiler-owned current-actor intrinsic, never a root/import/value binding; the ordinary Java main frontend still rejects unlowered sealed state. Java #3020 provides a separate stacked implementation on a closed, unmerged sealed-state parent; it must be re-integrated onto authenticated, current actor-owned critical MIR rather than pulled into this qualifier stack.

## Change provenance and verification

- Parent current-main receiver integration: Java #3137 (ports #3010 protection + distinct #3013 cases).
- Source qualifier reference: draft Java #2155; parser/types/ownership/Graal paths were selectively forward-ported, preserving newer source files.
- Suite: `ImplicitSelfMethodCallTest` (18 test methods; former value-reification positive switched to fail-closed negative), `ImportsQualifiedCallTest` (16 test methods).
- Linked .ores guest fixture checks `7|7|99|23`, in JVM mode; optional Graal native AOT/hybrid profiles require separate real exact-tree receipts.
- `ci/run_class_qualifier_proof.sh` requires **34** executed Surefire cases, zero errors/skips, linked guest output, exact tree SHA, clean worktree and authorized source policy.
- **NOT_EXECUTED**: no Maven/JDK25/GraalVM/Native Image or guest assertions were executed on the current PR head. Source-org workflow failures at `steps=[]` prove only runner admission failure. No private source was exported to an unapproved carrier.

Review the independently pinned current commit and root tree after every amendment. Do not merge based on authored tests, source grep, or zero-step workflow conclusions.

# Compile-time class proxy v1 (AOT-only)

Status: **candidate implementation / unverified until exact-head executed guest tests**.

AttoLang class proxies are **compiler-generated delegation classes**, not JavaScript
`Proxy`, JVM reflection, runtime bytecode, dynamic subclass loaders, runtime
method-name lookup, `Reflection.Emit`, or JIT code generation.

## Source-level entry point (v1)

```atto
define class Foo as
  pub greet(int value): int {
    return value + 1;
  }
end

pub routine main(): void {
  val proxy = new Proxy(new Foo());
  stdio.stdout.write(proxy.greet(41));
  return;
}
```

Only the syntax `new Proxy(new LocalClass(...))` is admitted. `Proxy` is a
compile-time intrinsic **not** a runtime allocation type. The compiler preserves
the exact wrapped construction expression, rewrites the outer expression to
`new FooProxy(new Foo(...))`, and emits an ordinary `FooProxy` declaration in
the same module as `Foo`. This AST is then fed to the ordinary type and
ownership checkers and ordinary code generation.

Conceptual class emitted (not a second user-facing source language):

```atto
define class FooProxy as
  val Foo __proxy_target;
  constructor(Foo target) {
    self.__proxy_target = target;
  }
  pub greet(int value): int {
    return self.__proxy_target.greet(value);
  }
end
```

No runtime specialization is needed; calls can be inlined/tree-shaken by native
backends subject to ownership/identity/effect rules. The wrapper is **nominally
distinct from Foo** and does not claim to be a subtype of Foo. The wrapper and
target have different identities. Internal target self-calls do not traverse
the proxy.

## What is deliberately not generated

- Private/protected members are never exposed. Static methods and constructors
  are not instance methods and are not mirrored. An explicit static façade can
  be added later.
- Generic class specialization, inherited/abstract classes and interfaces,
  actor instances, effectful/suspending/async/structural methods, generic
  methods, annotated-field-generated accessors and mutable/structural parameter
  contracts are **rejected with AOT_PROXY_UNSUPPORTED**, not silently proxied.
- This first slice does not accept `new Proxy(existing)` or inferred non-constructor
  target expressions. That requires verified source-value type inference,
  ownership transfer and alias analysis.
- Hooks (`before`, `after`, `around`, `proceed`) are NOT implemented.
- No runtime override interception, no auto-dispatch for internal self-calls,
  no actor transport proxy and no automatic static-method interception.
- Nothing in this change proves zero tracing GC, hard real time, native image
  execution, or multi-backend parity.

## Security hardening on the draft branch

The generator now verifies synthetic name uniqueness across compilation-unit
declarations and explicit import names/aliases; wildcard imports reject until
the qualified symbol table can prove resolution. An imported `Proxy` name
cannot silently bind to the compiler intrinsic.

Each public synchronous instance method must have a distinct name/explicit-arity
slot. Identical slot identities reject even if parameter types differ.
Method annotations are rejected rather than silently dropped: preserved
`@DefaultArgVals`, capability, visibility and other metadata need their
own compiler proof before forwarding is legal.

Borrowed (including mutable or nested-generic borrowed), inferred, and
function-valued parameter/return signatures are rejected until reborrowing,
ownership and closure transport can be validated for generated calls. Static
methods remain excluded, and internal target `self` calls are not intercepted.
Wrapping does not confer a new authority or make the generated nominal type a
subclass of the target. Compilers must use the ordinary statically checked
forwarder call: no speculative direct implementation binding that would bypass
future approved virtual dispatch rules.

Regression coverage: the Java test class checks same-arity ambiguity, import
and cross-module collision, annotations, nested borrowed values and function
parameters. The common-source contract adds negative `.atto` fixtures for
annotation loss and duplicate slots.

**Execution status:** Exact PR-head GitHub Actions job
[AOT class proxy exact-head guest gate](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/workflows/aot-class-proxy-exact-head.yml)
ran into runner admission failure (`steps=null`) at
`cdf765fca6ecd0057ecae9e12298623ee591e374`. No compiled guest
test or standalone Native Image success is claimed. Revalidate the subsequent
branch commits too. Do not mirror private compiler source to another
organization without explicit approved carrier terms.

## Additional fail-closed admissions (2026-11-10)

Public method signatures containing unresolved local type aliases also
reject until complete alias expansion and ownership proofs are available.
Otherwise `AliasFoo = Foo` could evade the direct/nested return escape guard.

The generated type must not select a local target `Foo` when an explicit
import or namespace alias also binds `Foo`. The compiler rejects imported
target-name capture instead of relying on unresolved symbol precedence.

Annotated constructors are rejected until wrapper metadata and effect rules are
proven. Mutable `struct` arguments/returns, including nested generic types,
also reject rather than forwarding mutable authority without a borrow proof.

The Java JUnit suite now declares **17** focused checks and the source-org
exact-head CI gate requires parsed Surefire XML with at least 17 test cases,
zero skipped/failures/errors, and no failure/error/skipped XML nodes.

An alternate private CI carrier at `opto-sync-test/go-java-validation` has an
independently reviewable **source-free** [PR #40](https://github.com/opto-sync-test/go-java-validation/pull/40)
for `ci/test-plans/java-pr2650-proxy.json`. It runs the targeted compiler suite
offline and verifies its XML. This plan is **not** an authorization to export
private source. The carrier workflow still executes its source plan on an
ordinary runner and needs independent no-network/unprivileged isolation
hardening before any private-source copy, in addition to source-main
protection, approved destination and exact-tree/plan attestation.

## Wrapped-target escape admission

A public method returning the proxied class itself (`Foo`, `self`, or a
nested `Option<Foo>`/other generic shape) is currently **unsupported**:
the runtime could return the wrapped `Foo` identity, bypassing `FooProxy`.
Method signatures exposing function-valued aliases (`Fnc`/`Function`) also
reject. This is conservative: a method returning a separate `Foo` may be
perfectly safe, but v1 has no proof of alias identity or proxy re-wrapping.
Later compiler passes can permit it with an explicit ownership-checked
identity-preserving adapter; do not silently expose the inner target.

`AotClassProxyLanguageTest` now defines 13 checks; its constructor-once
case includes guest execution, not just an AST shape assertion. All checks
still require executed CI evidence before any passing claim. The source
repository's private-CI policy requires a reviewed destination allowlist,
pinned test plan and immutable identity, and verified protected origin before
any private tree transfer. The user-requested token-free cross-organization
Git-tree mirror is **not an exemption** from these controls.

## Required evidence and follow-up

1. Java: execute `AotClassProxyLanguageTest` in hosted CI at the exact PR
   head. Confirm parser/type/ownership admission and real guest output.
2. Java/GraalVM: compile a standalone Native Image and confirm same guest
   output, no runtime generation, no unwanted reflection configuration.
3. LLVM and .NET NativeAOT: implement the same pre-typed lowering and check
   exact binary execution. Keep unsupported method surfaces fail-closed.
4. Wasm and Forge/Cranelift: implement the same class-lowering contract;
   ensure no JS `Proxy` dependency or dynamic host trampoline.
5. Add static façade tests, borrowed owned target tests, typed `around`
   effects, repeated construction, constructors that throw, nested proxies,
   generics, cross-module targets and every actor isolation negative.
6. Verify compiler/generated trees, executed test steps, exact artifact
   hashes and independent reviews. Hosted runs with `steps=[]` or
   `steps=null` are runner admission failures, **not compiler evidence**.

The generated implementation lives in
`src/main/java/dev/oreslang/ast/StaticProxySynthesis.java`; source
recognition is in `src/main/java/dev/oreslang/parser/Parser.java`.
The generated class contains normal typed AST members only; backends must not
replace it with unverified runtime reflection.


## 2026-11-17 source admission audit: opaque return identities

The generated class is a delegation wrapper, **not yet a virtual
interceptor**. It cannot be used as a security boundary that guarantees
every call to the wrapped target passes through a proxy. In particular,
target-internal self calls and any aliases published by the target
constructor bypass its forwarders.

The conservative compiler now rejects public return types whose declared
shape could erase the target identity: `Any`, `Object`, `Unknown`,
`Dynamic` (including their lower-case spellings or an unbounded `?`),
recursively through `Option`, `Result`, arrays and other type arguments.
Returning an opaque value might be safe for some implementations, but until
full value-origin and ownership analysis can prove that it does not contain
the wrapped target, emitting a method would incorrectly imply a checked
proxy boundary. It also rejects explicit method parameter names `self`
and `__proxy_target` that can shadow reserved generated receiver/storage.

Three new focused JUnit cases bring the declared suite to **20 tests**.
The exact-head CI admission gate now requires >=20 executed passing cases.
These are **defined assertions only** pending actual compiler-backed guest
execution; no new positive or negative guest receipt is claimed.

The eventual virtual-interceptor feature must separately prove receiver
vtable dispatch, a linear typed `proceed` operation, ownership and
effect preservation, no target identity escape, and no bypass through
unintended target aliases. The portable proposal belongs to
`atto-lang/atto-spec`; this draft cannot be described as implementing
virtual interception.

## Experimental statically typed before-interceptor candidate (2026-11-17)

This *stacked draft branch* adds an **opt-in** second constructor operand,
without changing the original one-operand `new Proxy(new Foo(...))` form.
The additional operand must be an inline, statically named `new LocalHooks(...)`,
not a runtime object, inferred name, interface, actor or reflection descriptor.

```atto
define class Counter as
    pub add(int value): int { return value + 1; }
end

define class CounterHooks as
    pub before_add(int value): void {
        stdio.stdout.write(7);
        return;
    }
end

pub routine main(): void {
    val wrapped = new Proxy(new Counter(), new CounterHooks());
    stdio.stdout.write(wrapped.add(41)); // expected: "742"
    return;
}
```

The parser rewrites this to
`new CounterWithCounterHooksProxy(new Counter(), new CounterHooks())`.
The synthesizer emits two private fields and a constructor storing them.
For every public eligible target method, it generates an ordinary typed method
that invokes `self.__proxy_hooks.before_<method>(args)` and then delegates
to `self.__proxy_target.<method>(args)`. Both calls use the established
name-and-arity dispatch table and ordinary type/ownership checking. Hooks
must be public synchronous void methods with the exact target parameter
types and no declared effects or annotations. A before hook may receive only\nclearly copyable immutable scalar arguments (int/float/bool/String), because\nthe generated method must subsequently pass those same arguments to its target.\nThis draft does not yet prove inferred effects or hook-body purity; that proof\nis required before calling it an effect-safe interception boundary. Missing, ambiguous, extra,
private, asynchronous, effectful, structurally typed, or mismatched hooks
fail closed. Target and hook must be ordinary, concrete, nongeneric classes
declared in the same module. Synthetic names, receiver names and storage
names cannot collide.

The target constructor and hook constructor are each evaluated exactly once,
left-to-right as arguments to the compiler-generated constructor. Each
*normal-returning* call runs its before hook once and then its original target
method once. A hook failure aborts the call before delegation under ordinary
language failure semantics; no exception is silently swallowed.

**Important distinction:** this is a first typed *before* interception slice,
not a complete `around` or `proceed` implementation. It introduces no
untyped method-name reflection, bypasses neither the original target's
internal `self` calls nor independent target aliases, and does not make a
proxy an airtight authority boundary. In particular, **no one-shot
continuation capability is exposed**, so this slice cannot claim the full
virtual-interceptor contract in `atto-spec#83`.

This branch defines **35** JUnit cases, including fifteen new opt-in hook
regressions. The source-org workflow demands nonempty exact-head passing
Surefire receipts for all 35. Until executed, all backend tests and
standalone native AOT verification remain **NOT_EXECUTED**. The source
prototype may not be promoted or merged on a host-only or zero-step
workflow receipt.

The compiler currently represents only declared checked exceptional effects\n(throws/raises/panics). A missing declaration is **not** a proof of\nside-effect freedom or transitive exceptional closure: ordinary logging and\nother externally visible calls can still occur in a before hook. This\nprototype does not claim inferred IO purity, async/suspension closure, or\nchecked propagation across arbitrary hook callees. Those checks remain an\nexplicit merge gate.\n
### Direct suspension rejection

The experimental before-hook synthesizer now refuses explicit source
`await`, task scopes, tasks, runtime intrinsics, channel/select operations,
lambdas, defer, and loops (which may cooperate), including those inside
supported conditional/match/try expression and statement trees. New unit
fixtures build both direct and nested suspension ASTs and require an
`AOT_PROXY_UNSUPPORTED` diagnostic rather than permitting the target method
to run before hook completion.

This is a *syntactic*, **not transitive**, proof. An ordinary helper call
may still hide suspension, panic, IO, or external capability operations.
Complete checked call-graph/effect analysis, dynamic-dispatch closure and
ownership proof are required before this experimental source form becomes
a normative or merge-ready interceptor feature.

### Closed-world transitive helper admission

The candidate now computes the reachable synchronous hook call graph by
existing name-and-arity slots. It accepts local `self.helper(...)` calls
whose method signatures, argument and result shapes are scalar and whose
bodies recursively use supported synchronous statements/expressions.
Recursion, cycles, unresolved/dynamic dispatch, mutable/borrowed helper
parameters, unknown method calls, effect-declared methods and unproven
suspension all fail closed. A bounded traversal (32 frames / 4096 nodes)
prevents adversarial graph blowups. The only explicitly recognized external
operation is `stdio.stdout.write(...)` or `stdio.stderr.write(...)`,
which remains intentional observable IO (NOT purity, infallibility, or a
general effect-elision exemption); the admission gate rejects direct
namespace/parameter/field shadowing. Source loops, nested closures,
assignments, aggregate construction and unknown AST types are rejected
rather than speculatively treated as synchronous.

This is still a **restricted proof**, not full effect inference. Target method
bodies and imported/dynamically linked helpers require separate analysis.
No claim is made that Java JUnit, GraalVM Native Image or other backend
tests passed until a source-executing runner produces an exact-head receipt.

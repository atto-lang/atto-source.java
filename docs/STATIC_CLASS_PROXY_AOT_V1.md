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

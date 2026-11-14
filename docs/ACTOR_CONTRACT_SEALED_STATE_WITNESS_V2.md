# Actor contract sealed-state requirements v2 — Java static compiler slice

**Status: DRAFT.** This branch is stacked on the manifest-owned declared actor compiler gate (#2901). It changes the actual Java parser, TypeChecker and static ActorContractWitness linker. It does **not** remove `DECLARED_ACTOR_CODEGEN_UNSUPPORTED`, issue ActorRef capabilities, or enable unverified actor-state lowering.

## Syntax: actor contracts can require private state data

```atto
namespace foo;

define pub contract ActorXYZ for actor as
  let int x;
  val bool ready;
  mtd bar(int delta): int;
end

declare pub actor Foo implements ActorXYZ;

define state on actor Foo as {
  let int x : 5;
  val bool ready : true;
}

pub mtd (Foo f) bar(int delta): int {
  return delta;
}
```

Each file may be split across a complete, verified same-package namespace source set. The physical four-file fixture is under `contracts/declared-actor-state-witness-v2`.

**Semantic separation:** Ordinary traits/interfaces may require callable behavior, but they never receive a private state loan or supervisor CONTROL capability. `contract ... for actor` requires **schema members**, not public fields. Only the compiler can verify a same-actor sealed `define state on actor` attachment against these requirements. A method such as `config(): Config` remains an ordinary behavior requirement; the state contract may independently require that the backing field is present and initialized.

## Checks

- Explicit `let T name;` and `val T name;` are the only admitted actor state obligations. Bare `T name;`, arbitrary annotations, modifiers, `const`, structural or generic types, and borrowed aliases are rejected in this narrow v2.
- v2 supports exact scalar `int` and `bool` state fields only, consistent with the current attached-state AST. `let` versus `val` is part of the witness; no coercion, inferred types or implicit storage synthesis.
- Each required field must have a unique attached-state declaration on the exact actor's namespace/package, with identical type and binding and an initialized literal value. Missing schema, missing field, mismatched binding/type, duplicate requirements from multiple contracts and method/state name overlap are errors.
- An ordinary inline actor's fields are NOT evidence for this contract; the normal inline actor typechecker rejects a state obligation until sealed attached-state linking is integrated.
- `ActorContractWitness` and `PackageNamespaceBuild` remain static compiler metadata. All executable source paths must continue to fail closed before codegen until critical read/write loans, actor-owned storage, initializer completion and generation-fenced scheduler dispatch are implemented.

## Audit / release gate

The existing `DeclaredActorContractWitnessTest` JUnit suite now includes positive and negative state witness cases plus a physical four-file fixture. Run it on an exact immutable Java source head with JDK 25 / real parser and TypeChecker; collect Surefire XML and zero skipped tests. Source-org Actions may fail before runner admission. Do not label that as a passed or failed test execution, and do not promote LLVM/.NET/Wasm/Forge as implemented from this parser-only change.

**Mandatory next integration:** Merge/reconcile the distinct Java external init/receive runtime branch (#2889 and its lifecycle stop-fence child) with this manifest/static state witness before enabling production declared-actor codegen. Then implement verified attached state allocation and `critical-read`/`critical-write` IR, real guest tests for the corpus, supervisor failure and teardown, and independent native-AOT parity.

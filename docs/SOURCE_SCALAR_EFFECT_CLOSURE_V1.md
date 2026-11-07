# Source-derived scalar effect closure v1

Status: **narrow Java/Graal compiler-source analysis**, not broadly admitted
effects syntax and not runtime or AOT certification.

## Actual implementation

`ScalarSourceEffectFactsV1.fromSource(String)` reparses real `.ores`
source and runs `TypeChecker.check` (including ownership checking) before
constructing a compiler-owned `EffectContracts.Graph`. No caller may supply
claimed-pure nodes, direct-call edges, arbitrary imported symbol tables,
untrusted proof JSON or runtime reflection.

The source slice is intentionally small: one **unqualified, unannotated**
file-root module, no namespace/imports/module contracts, only zero-argument
nongeneric `fnc` declarations (not actor functions or `routine` declarations),
and direct zero-argument calls. Admitted return types are restricted to unparameterized `int`, `bool`, `void` and `string`; other representations—including `any`, unions and floating-point—remain unproved until their typed ABI and boxing/allocation costs are verified. The file-root boundary is verified before and
after type checking so normalization cannot erase its admission constraints.
Integer/Boolean scalar literal returns are admitted with empty direct
effects; string literals conservatively count as allocation. Optional block
statements are traversed, and async/generator/suspending declarations,
`await`, Tasks, and channel/dynamic select forms carry effects. All other
language constructs, including unproven operators, variable aliases, local
bindings, methods, overloads, FFI, annotations and new or future AST nodes,
yield `UNKNOWN_CALL` or refuse admission. That is deliberately restrictive
until typed source/HIR proof of each node is implemented.

No source declarations in an unrecognized namespace, module or callable kind are eligible for a zero-effect summary. The graph computes transitive effects to a finite fixed point through
recursion. It is not a termination proof; a recursive function with no
observable effects can be effect-free and still be unbounded/RT-unsafe.

The `EffectContracts` vocabulary now includes three resource/control
categories (`ALLOC`, `BLOCK`, `SUSPEND`), distinct from observable effects.
Observational `pure` may still allocate (see `PURE.md`), while a separate
strict no-allocation/no-suspend profile rejects those effects. This patch
does **not** enable `pure` source declarations or any unchecked effect
annotations.

## Required implementation follow-up

1. Replace short-name based scalar lookup with compiler-established,
   qualified, overload-resolved source call identities including import
   provenance; reject lexical value/function shadowing and ambiguous calls.
2. Traverse all checked AST/HIR constructs with source location and identity,
   type witnesses for numeric operators, closure captures, dynamic dispatch
   target sets and exact ownership/loan scopes. No AST shape is assumed
   effect-free solely because no visitor handles it.
3. Model transitive allocation bounds, escaping borrows, blocking duration,
   async continuations, actor scheduler/message transport, exceptions, panic,
   cleanup and FFI signatures. Unknown ABI/effect contracts fail closed.
4. Integrate with checked `pure`, `using`, `requires`, RT entrypoint and
   typed MIR verification. No user annotation can upgrade an unknown effect
   into an authoritative compiler proof.
5. Generate positive and mutated negative `.ores` cases compiled into
   **program-specific GraalVM Native Image guest code** and LLVM native
   executable guest code. An interpreter packaged in Native Image, C++/Java
   host helper tests, or CI jobs with zero steps cannot qualify guest AOT.
6. For hard RT, separately prove call-graph termination/resource/scheduling
   bounds, no unbounded allocation/IO/await and physical WCET assumptions.
   An empty effect set from this narrow analyzer is **not** an RT certificate.

## Test scope

`ScalarSourceEffectFactsV1Test` exercises actual compiler-parsed positive
function chains, namespace/module/routine negative admissions, source-derived allocation propagation, conservative
rejection of unverified operators/local aliases, missing names and size
limits, and a recursion example documenting the separation of effect
closure and termination. Existing formal graph tests remain part of the
exact-head workflow.

### Review requirements

- Executed JDK25/Maven tests and build provenance on the exact PR head
- No zero-step/skipped Actions counted as green
- Source-to-symbol edge integrity and import/alias mutation review
- Independent review before making the source checker a production gate

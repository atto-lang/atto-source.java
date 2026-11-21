# AttoLang M3 — iterative scalar execution frames (Java/GraalVM subset)

**Status: draft implementation, NOT verified compiler-backed or full per-actor
execution-stack isolation.** Stack on Java PR #2628, with the following
existing dependencies: #2627 → #2624 → #2505.

## Compiler admission and interpreter execution

`ScalarFrameMachine` accepts only **closed** source-AST call graphs meeting
every precondition:

- `fnc` (never `routine`, generators, actors, async or `suspends`);
  no type parameters, macros/annotations, trap, structural/nlex modifiers,
  declared effects, mutable parameters or imported functions.
- Root-module `int` parameters (zero, one or two) and `int` return type;
  literal integer values, direct parameter reads, pure `+/-/*`, numeric
  comparisons/equality, direct typed function calls, explicit returns and
  conditionals with an exhaustive `else` return.
- All reachable functions must pass **before** executing any guest bytecode.
  Calls through closures, dynamically dispatched methods, named generic args,
  non-local data, annotations, method/actor boundaries, arbitrary I/O, async
  code, ownership operations, defer/exception handlers, nested statements,
  mutable state, and loops are **not admitted**. A rejected graph uses the
  existing bounded evaluator without speculative partial execution.
- Compilation is capped at 64 reachable functions, 1,024 bytecode
  instructions/function, and 64 levels of AST nesting. Runtime operand
  stack is bounded to 64 values per activation; only integral/boolean
  primitive values are admitted.

The lowered bytecode carries explicit `pc` locations for branch, call and
return, and the evaluator runs an **iterative** activation stack. Non-tail
calls push a new `GuestInvocationFrame` lease, bounded by the existing
64-call synchronous guard; return pops that lease. Proper direct or
mutual tail calls replace the current activation and rewrite its
descriptor, without growing the JVM call stack or reserving a second
actor-owned frame.

The existing M2 guard already reserves **one 32-byte frame per non-tail
invocation**. M3 writes its two signed 64-bit parameter slots at byte
offsets 16 and 24 of that same actor-owned allocation, following the
16-byte kind/arity/tail-hop descriptor. After tail transfer, unused
parameter slots are cleared. No second actor-frame charge exists.

**Honesty about ownership:** temporary operand values, code PCs, and
per-activation Java bookkeeping still live in bounded Java-managed
objects. M3 does not implement all guest locals or operand spill slots
inside the actor arena. The 32-byte layout is a provisional bootstrap
descriptor, not a published AttoLang ABI.

## Authored tests

The new `ScalarFrameMachineTest` suite (11 JUnit cases) constructs genuine
AttoLang AST records and exercises:

- 60 nested non-tail calls, value 1,830, with an asserted constant
  Java carrier stack-depth bound;
- 100,000 tail calls with one lease, and 100,000 mutual tail transfers;
- sibling recursive Fibonacci calls with return-PC/value-stack fidelity;
- 100,000 non-tail calls causing a controlled `GuestCallDepthExceededException`
  by frame admission, not a native `StackOverflowError`;
- closed-graph negative cases for unsupported side effects, missing
  functions, globals, mutable parameters, `routine`, missing return paths,
  excessive AST depth and incorrect arguments.

Two additional `GuestInvocationFrameTest` cases test actor-owned signed
parameter slots, scrub on tail replacement, bounds, and refusal to pretend
non-actor invocations own an actor frame.

Three new canonical `.atto` fixtures and three `ActorGuestInvocationLanguageTest`
methods exercise real source actor execution:

- `stackless-mutual-tail.atto` expects `1` after 100,000 mutual tail hops;
- `stackless-fibonacci.atto` expects `21` using two recursive calls per step;
- `stackless-fallback-binding.atto` expects `6` and confirms a local-binding
  function stays on the original interpreter path.

The previous #2628 fixtures for non-tail sum, 100,000 direct tail hops and
actor A exhaustion with actor B continuing remain part of the acceptance
set. These are **authored but NOT_EXECUTED** unless exact-head Maven and
compiler-backed guest receipts are obtained.

Run on an admitted, exact-head runner:

```sh
mvn -Dtest=ScalarFrameMachineTest,ActorGuestInvocationLanguageTest,GuestInvocationFrameTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test
```

Capture Git head, Git tree, fixture blobs, JDK and compiler versions, test
counts, nonzero executed job steps, stdout, fatal status and process exit.
`steps=[]` and `steps=null` are runner-admission failures, NOT failed
compiler parity.

## Outstanding release gates

This PR deliberately does **not** lower general `eval(Ast.Expr)`,
function/method locals beyond two integers, catch/defer/finally,
multi-typed operands, ownership and borrow state, task suspension, or
`SourceTask` continuations. The existing evaluator and FFI still use
the JVM native carrier stack. Heap-resident Java activation objects
are bounded but NOT wholly actor-owned byte memory. Java/Graal stack
containment is therefore only implemented for the admitted scalar subset,
not an AttoLang-wide guarantee.

Next, build a typed heap-frame instruction set covering all guest
expression/statement forms, return PCs and cleanup edges; integrate
stable `SourceTask` execution and await/resume, actor generation fencing,
non-reentrant turns, supervisor CONTROL, cancellation zeroization, and
native interop quotas. Verify compiled `.atto` actor A stack failure,
actor B survival/restart, and process survival on low native stacks
before claiming general per-actor execution stack isolation or
five-backend parity.

No catch-and-recover of JVM `StackOverflowError`, native SIGSEGV,
or other unchecked host/FFI faults is permitted.

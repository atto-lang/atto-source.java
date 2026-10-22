# Oreslang portable real-time primitive backend — stage 0

Status: **experimental, opt-in, source-level primitive admission**. NOT an RTSJ-certified no-heap actor, physical arena implementation, whole-language effect analyzer, or WCET proof.

## Entry point

OresCompiler.compileRealtimePrimitive(source, entry, arity, budget, outputDir)

Runs the existing full TypeChecker and OwnershipChecker passes, then rejects all operations outside a deliberately closed subset. RealtimePrimitiveCompiler.analyze(checkedProgram, ...) is for independently testing AST analysis and MUST NOT receive unchecked ASTs as a basis for compiler-endorsed guarantees.

Supported: one module with no imports or module contracts; synchronous free functions; explicit i32/bool arguments/results; initialized primitive locals, return, if/else, supported bounded primitive unary/binary operations; statically resolved same-module calls.

Rejected: actors, tasks, async/await, arena references, class/object allocation, string/list/array/reference values, FFI, runtime rt operations, reflection, dynamic calls, imports, class fields, annotations, exceptions, division/remainder, loops, recursion and generics. Division is excluded because ArithmeticException is a hidden exceptional path. Bounded loops await CFG/induction proof.

## Resource assurance

SourceReport includes a transitive source AST/call expansion bound, taking the maximum branch path, and a maximum call depth. Sums are checked for overflow and unknown callees fail closed. These are NOT Java bytecode-count, heap-use, maximum machine stack, microsecond or nanosecond bounds. Assurance remains SOURCE_AST_SUBSET_ONLY;BYTECODE_AND_WCET_UNVERIFIED.

Admitted AST nodes use scalar values, without explicit allocation, reference copies or suspensions. That does not prove GC independence or physical actor-region safety: Java compilation, class loading, VM safepoints, native compilation, warmup, scheduling and all target facilities remain outside this source-level proof. Oreslang/JVM numeric overflow semantic equivalence remains an unproven prerequisite.

## Portable backend

Emits one Java source unit compiled with a full JDK using --release 8 -g:none -proc:none. Result: RtPrimitiveModule.class, actual ordinary JVM bytecode, not a Truffle mock. The API returns its SHA-256 for exact-output downstream auditing.

Before claiming the emitted code real-time compatible:

1. Check Oreslang vs JVM numeric semantics, including overflow, and all reachable source lowering.
2. Use JVM bytecode verification and oreslang-runtime.realtime-java/tools/rt_bytecode_audit.py on the exact classfile.
3. Prove memory-region lifetimes and no-heap thread operation on a named, version-pinned vendor VM.
4. Analyze target OS, priorities, interrupts, GC interference, CPU affinity and other scheduling resources.
5. Establish target-specific WCET and whole-system response-time schedulability. Source steps and measured benchmarks are not WCET proofs.

A production hard-real-time actor, including physical actor-local arenas, bounded mailbox admission and continuation handling, is NOT implemented by this stage.

## Tests

RealtimePrimitiveSmoke compiles the actual Oreslang AST and portable compiler with Java 21, builds Java 8 target bytecode, executes it outside any claimed critical real-time region, tests negative admission and budgets, and invokes the independent bytecode filter in hosted CI. No vendor SDK or credentials are required.

Private cross-organization test mirrors must be allowlisted and Git-blob matched, with no secrets or unrelated runtime/application source. Treat zero-step CI as non-execution, never as a passing or failing compiler test.

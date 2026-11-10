# Neuron actors v0 — scalar inference ABI (AttoLang)

**Status:** candidate Java/GraalVM compiler implementation; not certified by this document. This change does not establish LLVM, .NET NativeAOT, Wasm, Forge/Cranelift or JS compiler parity, nor GPU execution or automatic differentiation.

## Goal

`define neuron actor A as ... end` is a *specialized actor declaration*, not an independent scheduler, magic neural-network engine, or one-native-thread-per-neuron promise. Its actor identity, supervisor, nonreentrant turn, bounded mailbox, generation, owner-only mutable fields, and cleanup use the existing actor primitives. The computational graph (scalar neuron/edge nodes) is distinct from the physical actor execution graph: group/fuse many mathematical neurons per physical actor when advantageous.

The contextual word `neuron` is only recognized after `define` and before `actor`. It remains a valid identifier elsewhere.

## Executable scalar v0 source contract

```atto
define neuron actor LinearNeuron as
  let float weight;
  let float bias;

  on_start(): void {
    self.weight = 2.0;
    self.bias = 1.0;
    return;
  }

  pub forward(float input): float {
    return self.weight * input + self.bias;
  }

  receive(ActorMail<float> mail): void {
    self.send(self.forward(mail.value));
    self.end();
    return;
  }
end
```

Required by the candidate Java type checker:
- The class is an ordinary generic-transport actor with one logical message turn (1,1); `neuron actor` cannot be mixed with HTTP/TCP/WebSocket transport or `isoactor` in this initial slice.
- Exactly one nonstatic, public, synchronous `forward(float): float` instance method, with one value parameter, no generic parameters, no checked effects and no structural modifier.
- Exactly one **private** synchronous `receive(ActorMail<float>): void` instance method, nonstatic, nongeneric and without checked effects. It is a runtime mailbox entry point, not an API clients invoke directly.
- No generic neuron declaration yet. Ordinary actor field initialization, strict ownership, and lifecycle rules remain in force.
- Methods named `forward` or `receive` with a different arity, visibility or signature fail closed. Other methods are ordinary methods and cannot claim to implement training intrinsics.
- The new AST actor-role metadata survives annotation expansion and is not confused with connection transport.

The runtime does **not** automatically route `forward` replies: the `receive` body explicitly calls `self.forward`, then emits via `self.send`. External callers send a `float` using `ActorRef` and read `ActorOutput`; they cannot obtain the instance or borrow its weights.

See `examples/neuron-scalar-v0.atto` for a compilable guest candidate and `src/test/java/dev/oreslang/NeuronActorV0Test.java` for exact guest execution and malformed-source tests. **A test in a PR is not evidence it ran.**

## Desired typed v1 interface (design target, not active syntax/runtime)

A versioned public `NeuronRef<I, O>` should offer:
- `infer(Forward<I>) -> Future<Result<ForwardResult<O>, NeuronError>>`, carrying run/invocation, graph-edge and parameter-version identity, with bounded request/reply budgets.
- `backward(Backward<O>) -> Future<Result<InputGradient<I>, NeuronError>>`, with a VJP tied to the exact recorded forward invocation, immutable parameter snapshot and complete gradient fan-in.
- `begin_step(expected_microbatches, parameter_version)`, `accumulate(GradientContribution)`, `commit_step(OptimizerProposal)`, `cancel_step(reason)` as **supervisor-mediated** CONTROL/step operations with idempotent dedupe and atomic version publication.
- `snapshot() -> Future<NeuronSnapshot>` as an immutable, budgeted, versioned copy rather than a raw mutable state view.
- `connect(port, destination: NeuronRef<...>)` through a supervisor-validated acyclic typed graph, never by untrusted string identity.

The v1 *guest-implemented* neuron contract should provide pure `forward(input, weights)` and `vjp(input, upstream, weights)` hooks plus a declared activation (`linear`, `relu`, `sigmoid` etc), shape/dtype and parameter schema. Autodiff tapes and optimizer moments remain in single-owner actor state; no cross-actor mutable references. `commit_step`, cancellation and retries need runtime-owned correlation, generation fencing and quiescence, not user-defined method names with fake authority. All DATA envelopes require provenance, deadlines, bounded storage/credits and version validation; supervisor CONTROL must remain deliverable when DATA is saturated.

For single-scalar inference, `float` is the only admitted dtype. Rank/shape, f32/f64, sparse/dense tensors, batched fan-out, loss functions, dynamic graphs, training and automatic GPU placement are **not implemented** by this PR.

## Acceptance and negative tests

1. Parser/AST distinguish neuron from ordinary, HTTP and WebSocket actors; reject malformed modifiers, duplicate role and accidental `neuron class`.
2. Type checker rejects missing, private, async, wrongly typed or overloaded hooks and missing/wrong `receive`.
3. JUnit guest test must execute `.atto` to spawn a neuron, send scalar input, observe exact output and await shutdown. A parser-only success does not count.
4. Regression: standard `define actor`, `define serial http actor`, nondeclaration `neuron` identifier, and annotation-expansion metadata must continue to work.
5. Independent backend work for LLVM, .NET, Wasm, Forge, JS: each must compile/exe the same fixture and match negative diagnostics or fail closed before emitting an artifact.
6. Exact PR-head Git SHA, tree SHA and non-zero executed guest-test steps are mandatory before claiming execution. `steps=[]`, `steps=null` or `runner_id=0` is runner-admission failure, not proof. Host-only numerical or static canaries remain labeled separately.

Related existing references: `docs/ML_ACTOR_AUTODIFF_V0.md`, `docs/ML_ACTOR_PORTABILITY_V1.md`, `contracts/ml/v1/`. Keep `.ores` fixtures for legacy compatibility; all new guest code is `.atto`.

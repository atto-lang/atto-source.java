# AttoLang Cart guest execution slice (current Java branch)

Status: **draft — tests source-authored, NOT EXECUTED** until exact-head CI admits a runner.

This work is stacked on the current Java virtual-actor directory / supervisor
CONTROL / physical generation-fence stack:

1. #2759: runtime-local generation/physical actor retirement authority.
2. #2817: single-flight supervisor CONTROL terminal receipts and async decision fence.
3. #2821: versioned logical addresses and authenticated read-only directory.
4. This PR: compile and execute the first reusable Cart **transition** in actual
   AttoLang guest code on a host-managed physical worker.

## Guest source

- `contracts/virtual-actor-cart-v1/positive/cart_ops.atto` contains the entire
  arithmetic/application transition: the pure, checked `cart_step(current,
  delta)` AttoLang function.
- `contracts/virtual-actor-cart-v1/positive/cart_main.atto` imports and exercises this function via the production
  linked-source compiler and Graal/Truffle guest runner. Expected guest stdout
  is exactly `cart|2|7|5`, recorded in `contracts/virtual-actor-cart-v1/expected/cart_main.stdout`.
- `contracts/virtual-actor-cart-v1/cases.json` is the shared backend-neutral
  inventory with immutable Git blob identities, the expected stdout file,
  the protected-MIR rejection, and the checked i64 overflow vector.
- `contracts/virtual-actor-cart-v1/negative/virtual_cart_unsupported.atto` is a mandatory negative fixture for the
  desired `define virtual actor Cart` syntax. It MUST reject with
  `VIRTUAL_ACTOR_MIR_UNSUPPORTED` until the protected state/CONTROL MIR exists.
  It must not lower unsafely as an ordinary Java class or as an old isoactor.

The Java `AttoCartPhysicalActivationGuestTest` uses an intentionally
test-scoped, tiny Truffle `Context` / `OresEvalRootNode.INVOKE_PUBLIC_COMMAND`
bridge. One context is initialized **inside** each `ActorRuntime` physical
actor's factory and is closed only after the physical worker's `getDone()`
settles. The worker calls the compiled `.atto` function on each accepted turn;
it does not implement the Cart transition itself in Java.

This is a **host-managed activation prototype**, not guest-visible virtual
actors. Its temporary Java exception is only Truffle guest invocation and
test harness wiring; the reusable Cart algorithm lives in `.atto`.

## Safety properties exercised (once executed)

- A canonical logical Cart ID/address is inert on lookup (no physical actor).
- Generations are minted by the existing runtime ledger, never by a separate
  Cart counter; the host checks exact `ActorId` and supervisor ownership.
- The first physical actor performs two Cart transitions through compiled
  AttoLang guest execution, then reaches graceful stop and quiescence.
- A second physical actor binds the **same stable logical ID** with a higher
  generation, but a distinct `ActorId` and separately owned guest context.
- Old physical references and closed guest contexts cannot invoke the new
  physical actor. No mutable guest context or captured state crosses that
  boundary. Quantity starts from zero again because persistence is absent.
- Checked `int` arithmetic uses the AttoLang i64 domain and rejects overflow.
- An overflowing compiled guest transition inside a bound physical actor must
  terminate that actor, emit only an exact-generation sanitized supervisor
  CONTROL receipt, block successor reservation while the asynchronous policy
  is unsettled, and admit generation N+1 only after acknowledgement.
- Unsupported protected virtual declaration still rejects at the compiler
  type/MIR boundary. The negative test is not falsely counted as guest runtime.

## Remaining compiler / runtime feature work

The compiler's `Ast.VirtualActorDecl` captures protected method token
bodies, and `TypeChecker` intentionally rejects them with
`VIRTUAL_ACTOR_MIR_UNSUPPORTED`. To implement *actual*:

```atto
define virtual actor Cart as
  state { let int quantity: 0; }
  add(int n) -> void ::
    handle_state(critical-write |s| -> {
      s.quantity = s.quantity + n;
    });
  end
end
```

the next compiler PR must implement protected-state MIR with checked
borrow/exclusive write and compiler-owned heap/stack access, then type-safe
`VirtualActorRef[Cart]`, host activation/invocation lowering, and automatic
lazy reactivation under the existing supervisor/CONTROL ledger. No AST token
body may be treated as already typechecked or lowered. Maintain AOT legality;
no reflection, hidden codegen, bypass or JIT-only guest semantics.

Storage-provider durable CAS epochs and restore-before-publication are
separate prerequisites for state continuity across STOP or process restarts.
Until they exist, **do not claim Cart quantity persists across activations**.
The prototype explicitly resets state on a new physical incarnation.

Also unverified: Native Image, LLVM, .NET NativeAOT, Wasm, Forge and distributed
activation/placement. The `.atto` transition is portable source, but portability
needs independent exact-head executed backend receipts.

## Exact-head evidence requirement

Run `.github/workflows/atto-cart-virtual-guest-current-main.yml` with
actual nonzero job steps and `HEAD^{tree}` readback. It must execute
`AttoCartPhysicalActivationGuestTest` (5 cases) plus the 37 parent-stack host JUnit
methods and preserve Surefire XML showing nonzero passed tests with no errors,
failures or skips. A zero-step job is `NOT_EXECUTED`, never a Java compiler
failure, AOT certification or guest proof.

Private source mirroring requires the repository's
`docs/PRIVATE_CI_MIRRORS.md` authenticated private/private checks, exact
repository ID pinning and independent security approval, not just unused
GitHub Actions minutes in another organization.

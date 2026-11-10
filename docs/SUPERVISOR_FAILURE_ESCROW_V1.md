# Supervisor failure escrow v1: trusted CONTROL prototype

Status: **Java host-only prototype on a stacked draft**. This class is not called by `ActorRuntime`, not source `define supervisor` lowering, and not a native/guest physical cleanup receipt. It deliberately does **not** replace or merge the separate existing SupervisorFailureControl/ChildRegistry branch families.

## Transition contract

- **registerRoot/registerChild:** trusted runtime registers opaque physical ids and authentic incarnation generations. A terminal notice reservation is allocated BEFORE the child becomes visible; max 4,096 nodes / depth 64. Never expose this admission function to AttoLang guests.
- **fail(id):** terminalizes the node once and fences all descendants under the trusted ledger monitor; allows an already-fenced in-flight descendant itself to fail; repeated same-incarnation failure does not duplicate the notice.
- **deliver(id):** returns a package-private, object-identity CONTROL permit bound to the current nearest healthy ancestor, its generation, the child terminal notice, and the host ledger instance. It never uses actor DATA mailboxes or gives the controller access to the failed actor's sealed state.
- **acknowledge(permit):** validates the SAME object and CURRENT nearest live ancestor before removing the pending notice. A parent crash revokes previous deliveries even before redelivery; duplicates are inert.
- **snapshot/mayAdmit:** detached immutable diagnostic state and fail-closed admission checks; neither is a physical cleanup proof.

The trusted root guardian cannot fail/recover inside this substrate. For host process death, use an external process supervisor plus a crash-consistent ledger and fenced process generations. **In-memory receipt escrow is not disk durability.**

## Exact source-check evidence

Local JDK 21 `javac -Xlint:all -Werror` + `java` host-only run on the exact source/test Git blobs:

- Source: `aa0647213aeff80044e709c34cf65b4b845acb6f`
- Test: `22980520c28abc36d2600f37d9af0f1076c1c02c`
- Result: `PASS SupervisorFailureEscrowV1Standalone assertions=554`
- Includes 8 rounds × 64 concurrent failed-child terminal publications, old supervisor delivery rejection, ancestor takeover, sibling isolation, and repeat/forged/stale denial.
- Script `bash scripts/verify-supervisor-failure-escrow-v1.sh` verifies both Git blobs before running. Hosted exact-head workflow remains independently necessary.

## Explicit missing production wiring

1. Bind a trusted ActorRuntime child-admission transaction to a pre-reserved terminal escrow slot, with immutable supervisor and actor incarnation identity (no guest-mintable ids).
2. On actual terminal CONTROL from the actor scheduler, atomically mark failure and fence the full subtree. Prevent accepted DATA/child admission after the fence linearization point; count in-flight turns and independently await physical cleanup.
3. Replace standalone `fail` calls with real authenticated terminal producer, and integrate delivery/ack with the existing single-flight SupervisorProcess decision-commit fence. Do not ack before policy commits; if commit fails, retain the notice.
4. Connect `SupervisorLifecycleFsm` and `SupervisorShutdownTree` state transitions and physical receipts. Acknowledged failure DOES NOT imply the descendants are stopped or their heaps/frames/FFI loans reclaimed.
5. Distinguish terminal process guardian failure from guest supervisor policy failure. NativeAOT/Wasm/Forge/LLVM parity and compiled AttoLang guest tests remain **NOT_EXECUTED/NOT_CERTIFIED**.

This source intentionally has no actor references or capability export APIs. No claim of complete supervision-tree semantics or formal refinement. See conformance draft #659 and Java #1965.

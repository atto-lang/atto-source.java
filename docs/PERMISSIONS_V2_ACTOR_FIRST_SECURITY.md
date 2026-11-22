# Oreslang permissions v2: actor-first security boundary

**Status:** security design and partial Java/GraalVM enforcement; **not** an executed five-backend certification. Do not claim full permissions parity from the presence of this document or a source-level unit test.

## Threat model and authority order

Permissions are an intersection, never a union:

`effective = host ceiling ∩ process policy ∩ VM-isolate policy ∩ actor delegation ∩ package/module declared needs ∩ live revocation state`.

An actor may only receive **attenuated** capabilities from its parent, including when spawned from callbacks, exceptions, hot-loaded code, task chains, event handlers, or imported helpers. A helper or callback must never execute with its lexical module's broader authority instead of the calling actor's effective authority. Every host I/O operation must revalidate the *current* actor identity, trust domain, resource scope, and relevant revocation epoch.

**No ambient authority** in ordinary CLI programs, third-party packages, untrusted actors, or VM-isolates. Explicit trusted embedding authority is separate from ordinary CLI grants. Absence of a serialized scope envelope must not turn into unrestricted guest I/O. Deny takes precedence, and malformed or duplicated serialized policy fails closed. Keep `github.com/flags-2-env` the canonical CLI parser contract; no backend-specific surprise aliases or independently widening flag parsers.

## The `unsafe` and host-escape boundary

- The `unsafe` package must be **unresolvable and unimportable**, not merely unusable at call time, for untrusted actors and **all VM-isolates**. Reject static imports, deferred imports, aliases, reflection-generated exports and code-generation loaders. Do not include its exports in the guest module namespace.
- Raw FFI, arbitrary native calls, OS syscall access, unrestricted process launching, Java/.NET reflection, P/Invoke, WASI host import expansion, JVM host-class loading, and unmanaged native pointer escapes must not be hosted inside a VM-isolate. A trusted out-of-isolate service can expose specific audited, typed operations over a capability-checked IPC channel.
- The Java `IsolatePolicy` adversarial ceiling rejects the highest-risk host-escape capabilities as an initial fail-closed gate. That check is **not** a substitute for package resolution suppression, broker enforcement, or OS sandboxing for trusted isolate modes. A trusted host may still hold authority outside the isolate.

## Resource-scoped permission boundaries

| Kind | Required authorization identity |
| --- | --- |
| Read/write | Opened file/dir handle plus path anchor, device/inode or OS file ID; safe `openat2` or descriptor-relative strategy, symlink/junction policy and Unicode/volume alias normalization; recheck before final open |
| Network egress | Scheme/protocol, canonical hostname, port, resolved destination IP, address class, redirect target, proxy destination; DNS resolution and actual connect tied to one authorization |
| Network listener | Local bind address/interface/port and protocol, separately from egress; port 0 needs explicit policy and post-bind accounting |
| Environment | Exact variable name; separate read vs mutation and inherited-child-environment forwarding |
| Subprocess | Executable **content/identity**, argv constraint, cwd, supplied environment, inherited handles, child OS sandbox and resources; granting `run` must not grant a shell or an escape into broader host authority |
| FFI | No guest raw FFI. Broker-only checked operation ID, version, code signature, bounded argument types, memory ownership, cancellation, and audited result |
| Imports | Package/namespace identity, content hash and trusted origin, transitive graph policy, dynamic import policy, symlink traversal and runtime code loading |
| System/device | Distinct operations for clock/process info, entropy, GPU, MMIO, device handles, and privileged control; never a single implicit broad `sys` or `native` umbrella |

## Delegation, revocation and resource limits

1. Actor spawn passes a **sealed, unforgeable delegation token** with parent actor ID, child actor ID, target isolate ID, capability type, narrowed scope, expiry, use-count and policy epoch. Tokens are not serializable across untrusted boundaries; authorization is checked by the supervisor/host broker.
2. The child can delegate only a subset, and only if that grant is explicitly marked delegatable. Scope widening, renewal, or cross-tenant forwarding requires new authority from the supervisor. The recipient cannot recover a broader parent authority through its actor group, imports, task continuation, or native callbacks.
3. Revocation is linearizable with new admissions: increment the policy epoch, reject all future operations under an old epoch, and fence/drain or cancel outstanding host operations before reclaiming resources. Explain which already-completed I/O effects are not reversible.
4. Meter runtime resources per actor and VM-isolate: heap, arenas, mailbox bytes, CPU/reduction budget, wall time, open handles, sockets, DNS queries, outbound bytes, subprocesses, GPU time and native allocations. A quota failure must not be swallowed as an ordinary retry or convert to a wider policy.
5. Audit **every host boundary** with decision, reason code, actor/isolate lineage, policy hash+epoch, operation and scope, without logging secrets or full sensitive paths by default. Use a tamper-evident event stream where required. Prevent audit logging from being a new denial-of-service path.

## Fail-closed acceptance requirements

- Actor `A` may have `read:/tenant-A` while actor `B` in the **same host process** cannot use it, even via `A`'s imported helper or callback.
- An untrusted actor or VM-isolate cannot resolve `unsafe` symbols, load native libraries, invoke host reflection or spawn unrestricted subprocesses, even if the caller claims a broader legacy capability.
- Revoking a child permission while an asynchronous read/connect is in flight cannot admit a second operation or send a borrowed handle into another tenant.
- Reject symlink swaps, Unicode filesystem aliases, CIDR/resolved-IP discrepancies, DNS rebinding, IPv4 shorthand/hex aliases, network wildcard+port denial bypasses, redirect/proxy laundering, executable replacement and forged serialization.
- Enforce identical denial at compiled/interpreted/AOT runtime boundaries, not only in source analysis or a host-only policy simulation. For every backend, require actual guest I/O negative/positive tests tied to immutable code SHA/tree and real runner steps.
- An experimental backend without a safe implementation must reject the operation or profile instead of silently falling back to unrestricted host I/O.

## Implementation / review ledger

- **Java/GraalVM:** existing `RuntimePermissions`, `CapabilityChecker`, `IsolatePolicy`, and `OutboundEgressPolicy`; this branch hardens the adversarial capability ceiling. Still required: remove `unsafe` module exports from all VM-isolate import surfaces, enforce per-actor scoped resource grants (not just coarse capability subsets), resolve network matcher/CLI drafts, run JDK 25/GraalVM Native Image end-to-end tests.
- **LLVM:** reconcile current native scoped-policy/TCP drafts and instrument actual native host I/O operations; validate with Clang/GCC, ASan/UBSan and hard-real-time quotas.
- **.NET NativeAOT:** avoid P/Invoke, `NativeLibrary`, reflection, subprocess and dynamic assembly bypasses; prove native AOT guest authorization.
- **WASM:** deny imports by default; attach capability tokens to explicit WASI/JS host calls and worker messages, never trust a JS-only check.
- **Forge:** carry the same policy enforcement into native/JIT/AOT calls and mailbox ingress; reject unmanaged escape routes.
- **Conformance:** retain immutable v1 vector oracle, then introduce a distinct, independently reviewed v2 corpus for actor delegation, revocation, unsafe namespace absence, in-flight resource fencing and broker execution. Never silently rewrite v1 expected outputs.

**Release status:** blocked until verified, runner-executed guest tests, independent security review, and per-backend signed-off exact-head evidence. Do not merge a contract-only or compile-only check as runtime security proof.

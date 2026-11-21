# OresVM: one-process shared code, actor-owned mutable state

**Scope (2026-10-06): all actor kinds run in the same OS process.** The
runtime should keep immutable code/import definitions once per identity/version
instead of copying them into every actor. Cross-process executable-page sharing,
`mmap`, `memfd`, relocation, and ABI work remain deferred.

## Required invariants

- Mutable actor state is never shared merely because code is shared. Mailboxes,
  fields, closure environments, module initialization state, async frames, GC
  roots, capability state, and execution leases remain actor/context-owned.
- A code image is keyed by exact `(codeUnitId, SHA-256(sourceText))`.
  Different versions or source identities never alias.
- Capability admission is repeated for every destination context even when its
  immutable source/AST/call target is reused.
- Untrusted code is **private by default**. It joins the process-owned immutable
  image registry only after a trusted supervisor explicitly approves that exact
  code-unit identity and digest, and the source passes the untrusted capability
  policy.
- Approval of one untrusted root does not approve imports transitively. Each
  imported code unit requires its own exact approval.
- Sharing immutable code never grants a Java/Truffle host object, classloader,
  closure, mutable AST, actor reference, raw function pointer, or supervisor
  capability.
- Hot-reload generations pin old code images until their leases close; loading
  a new version does not mutate an actor already executing the previous image.

## What private/isolated actors now share

`SharedCodeRegistry` is a supervisor-owned process registry that reuses the
same `org.graalvm.polyglot.Source` and checked immutable `Ast.Program` for a
live code image.

Trusted/private contexts also use one explicit
`ProcessCodeEngine.shared()`. Oreslang is registered with
`TruffleLanguage.ContextPolicy.SHARED`. `OresEvalRootNode` contains only
immutable program data; mutable `Evaluator` state is obtained from the current
`OresContext` and therefore is not shared between actors.

The regression proof creates two different private Graal `Context` objects and
asserts:

1. both contexts reference the exact same `Source` object;
2. both contexts reference the same process `Engine`;
3. the contexts themselves are distinct;
4. evaluating the first context invokes `OresLanguage.parse` once; and
5. evaluating the second context does **not** increment that parse count.

That establishes reuse of the Engine-cached parsed call target/code tree across
isolated/private contexts, rather than merely sharing source bytes.

This is still not a proof that every machine-code page produced by the Graal JIT
is physically identical or resident only once. The verified claim is the
shared Engine/Source/parsed-call-target cache boundary.

## Untrusted actor sharing gate

Adversarial/untrusted contexts retain their separate sandbox/isolate boundary
and do **not** join `ProcessCodeEngine.shared()`. This is deliberate: code from
different trust domains must not gain ambient authority just to improve cache
reuse.

The trusted supervisor may call
`approveGuestCodeSharing(codeUnitId, sourceText)`. The registry records the
exact code-unit identity plus SHA-256 only after the source passes
`IsolatePolicy.untrustedActor()` capability admission.

`HotReloadManager` then behaves as follows:

- no approval: create a fresh `Source.cached(false)`, report
  `Generation.sharedCodeImage() == false`;
- exact approval: lease the process-owned immutable `Source`/checked AST and
  report `sharedCodeImage() == true`;
- changed bytes or renamed code unit: fail back to the unshared path.

The end-to-end regression loads the same untrusted program through two
managers before and after approval, proving private `Source` identities before
approval and identical shared `Source` identity after approval. It also proves
that a changed digest and renamed code unit remain unshared. Because adversarial
contexts intentionally use `spawnIsolate(true)`, this execution-level test is
run in the `polyglot-isolate` CI job after building the Oreslang native isolate
library; ordinary JVM verification skips only this isolate-dependent method.

**Important:** this host-side immutable image sharing does not claim that
untrusted actors share the trusted/private Graal JIT cache. Their sandbox
execution engine remains isolated.

## Security hardening: supervisor-scoped untrusted sharing

The historical process allowlist `(codeUnitId, sha256)` was
**not an authorization boundary**: one tenant's approval could let a separate
supervisor consume or revoke the same exact image by submitting the same path
and bytes. Draft supervisor-scoping remediation:

- A trusted supervisor calls
  `SharedCodeRegistry.process().newSupervisorScope(supervisorPolicy)` to
  mint an opaque object capability. The scope is bound to the particular
  registry and **supervisor policy instance**, not to a guest-chosen string,
  module path, matching policy contents or hash.
- A caller explicitly authorizes reuse through
  `HotReloadManager.forUntrustedActors(policy, profile, scope)`. Two
  managers may share code approval only if the *same trusted owner* passes
  the *same token* to each. Managers created without a supplied scope mint
  a private scope and never implicitly inherit another manager's grants.
- Approval and revocation keys include the scope identity. A second tenant's
  identical module name and digest do not inherit the first tenant's grant
  and cannot revoke it. Capability and content checks still run separately.
- The earlier `canShare()` result is only advisory. An adversarial loader
  must call `acquireApprovedForAdversarialSharing(scope, codeUnitId, source)`,
  which checks the **current** authorization and pins the Source atomically
  under the registry lock. After revocation/closure no new shared image
  leases may be acquired; already-pinned generations can drain normally.
- The old unscoped approval/revocation API fails closed. The old unscoped
  query returns false for source compatibility. Closing a scope revokes
  all its approvals and prevents future use; a manager closes only scopes
  it minted, not caller-owned shared authorities. Already-running code-image
  leases remain pinned until each generation actually retires.
- Tests cover equal-but-distinct supervisor policies, cross-supervisor
  revocation attempts, foreign/retired tokens, hostile source policy
  rejection, and explicit two-manager sharing.

**Integration note:** This is hardening for the historical Java #416 branch,
*not* proof that adversarial code may reuse the trusted host's compiled
instructions. The newer [Java #569](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/569)
and [#1275](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1275)
require a distinct ONE-UNTRUSTED-ENGINE code-cache boundary. When these
branches are reconciled, scoped sharing grants must apply only to the
matching trust-domain-local code image (or untrusted grants must remain
disabled); untrusted execution must not import trusted executable images.
No physical code-page sharing, cross-tenant fault isolation, or successful
exact-source native-isolate validation is claimed.

## Actor construction and lexical rules

- Actor `constructor(...)` remains rejected. Ordinary classes may have
  constructors; actors are runtime-created and eventually initialize through a
  lifecycle hook such as `on_start()`.
- A failed actor initialization rejects `ready` with the original failure,
  rather than replacing it with a generic terminated error.
- Lambda bodies use the slim arrow `->`.
- Classes, actors, and modules resolve only their own members, activation-local
  bindings, explicit imports, and capability-approved builtins. They do not
  implicitly reach file/module helper callables.
- Bound instance methods that are direct-call-only stay direct-call-only, and a
  closure cannot capture borrowed `self`; the shared-code work does not relax
  either rule.

## Acceptance evidence

The exact-tree CI mirror in a funded test organization verifies blob/mode
identity by Git tree SHA before results are trusted. On the validated tree,
normal JVM/Maven verification, examples, private actor isolation, untrusted
actor tests, lexical-boundary tests, readiness lifecycle tests, and
`SharedCodeRegistryTest` all execute against the same source tree.

The larger follow-up stress target remains: spawn at least 1,000 actors of each
kind with repeated imports, demonstrate that immutable code-cache cardinality
does not grow linearly with actor count, and independently verify mutable actor
state separation under concurrency.

## Deferred

Cross-OS-process read-only page mapping, shared native executable pages,
`memfd`, multi-process loader/ABI relocation, and PSS/RSS comparison are not
prerequisites for this same-process design.

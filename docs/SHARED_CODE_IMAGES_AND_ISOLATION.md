# OresVM: immutable code sharing and strict actor-state isolation

**Contract (2026-10-06):** Code is process-/image-shared; mutable execution
state belongs to its owner. **Never** clone imported executable code per actor.
Never mistake an immutable code image for permission to access its originating
module's mutable globals, host capabilities, closures, class instances, or
other actors' heaps.

## Actor API and construction

* Actors are not ordinary objects and **cannot declare a constructor**.
  The parser already rejects any actor \`constructor(...)\`, even an empty
  public constructor. Preserve that rule, including inherited/alias spelling.
* \`spawn Worker()\` (future source lowering) must allocate an \`ActorRef<Worker>\`
  and initialize actor-local field defaults in the actor's execution domain.
  Add a **distinct actor lifecycle hook** such as \`on_start(): void\` for
  user initialization. Execute it after identity, heap, policy and mailbox
  are established, before \`ready\` resolves. Do not call a class constructor
  in the spawning parent. An actor cannot be built with \`new Worker()\`.
* \`await w.ready\` resolves when actor-local startup finishes. Failure is
  observable, not a silent ready result. Constructors for ordinary classes
  remain valid and are unrelated to actor startup.
* Lambda implementations use **slim arrow**:
  \`const create = async || -> { const w = spawn Worker(); await w.ready; return w; };\`
  Never replace implementation arrows with \`=>\`.

## Memory domains

| Artifact | Shared actor | Private/isolated actor | Untrusted actor |
| --- | --- | --- | --- |
| Immutable source, import graph, checked AST, bytecode/code image | Share once per image/generation | Share read-only *without granting guest host access* | Share read-only via sandbox's validated code-image loader |
| Mutable module bindings, class static mutable state, initialization results | Owner/service-scoped; never inferred shared by the code image | Isolate-local or explicit supervisor-owned service | Isolate-local and zero ambient supervisor authority |
| Actor fields, class instances, closures, stack/async frames, GC roots | Actor-owned | Actor/isolate-owned | Actor/isolate-owned |
| Cross-actor communication | Serialized mailbox or explicit capability | Serialized mailbox | Serialized and sandbox-validated mailbox |
| Compiled runtime caches/invocation inline caches | May share only when immutable and security-neutral | Context-/isolate-local if mutable | Context-/isolate-local; must not leak host pointers |

\`shared actor\` denotes scheduling and the ability to reference approved
immutable pages, **not** shared mutable heap writes. Actor single-writer
discipline remains mandatory. \`rt proxy\` is an explicit bounded synchronized
capability, **not** an implicit consequence of importing code.

An \`import\` publishes an immutable **definition reference**, not a copy of
an initialized module object. Linking that definition into an actor must
install a *new, owner-scoped binding table* containing only the dependencies
explicitly imported by that source unit, with policy checks each time it is
bound. Imported classes and routines are definitions, not mutable static
singleton objects. Any module state with global/singleton semantics is
accessed via a separately declared authority/capability; a plain import
never confers direct read/write access to such state.

## Lexical isolation is a compile-time rule AND runtime rule

Inside class, actor class, and module bodies, name lookup can reach:

1. Local parameters, activation locals, lexical inner callables (subject to
   \`lex\` / \`nlex\`), their own declared fields/methods/functions.
2. Names from an explicit source-level \`import\`, with immutable definition
   identity and per-destination capability admission.
3. Approved built-ins, subject to actor/isolate capabilities.

No implicit lookup in sibling file-level callables, sibling modules, other
classes' static members, unqualified file globals, or the parent actor's
environment. Qualifying a name (\`OtherModule.foo\`, \`OtherClass.foo\`) must
NOT bypass the rule. \`import * as X\` exposes only the imported declaration
manifest, not a dynamic module object with ambient globals.

Apply this boundary in parser/name binding, type/ownership/effect checking,
AST lowering, linked-code resolution, reflection/interop, and the evaluator.
The current actor groundwork PR #415 blocks unqualified class-to-file
callable access but **does not yet satisfy these stronger rules**.

## Process-local implementation increment

\`SharedCodeRegistry\` is a supervisor-only, refcounted, content-addressed
cache of immutable Graal polyglot \`Source\` instances. \`HotReloadManager\`
acquires a lease per generation and releases it after its independent
\`Context\` closes. Identical \`codeUnitId + SHA-256(sourceText)\` yields
the same \`Source\` **object** across live generations/managers; byte-for-byte
source is checked even after a digest match. Distinct code-unit names and
versions are kept separate to preserve import/diagnostic identities.

This saves repeated \`Source\` construction and retains one host-side
immutable image per simultaneously live code identity. It **does not yet**
share parsed ASTs, full import graphs, Truffle call targets or machine-code
pages. \`OresLanguage\` currently declares \`ContextPolicy.EXCLUSIVE\` and
builds a new evaluator per context: that separation is intentional, since
an evaluator carries mutable module and closure state. Future caching can
share a deeply immutable *checked program/compiled definition graph*, but
not the stateful evaluator.

Each destination still performs its own policy/capability admission even
when using a previously validated image. Host-owned registry handles must
never become guest values or pass through actor mailboxes. Code data is
not included in actor heap accounting; mutable link tables and runtime
metadata ARE charged to their owner.

## Native / multi-process backend (mandatory before zero-copy claim)

Reusing a Java \`Source\` instance is **not equivalent** to physical shared
memory for isolated OS processes. The native backend needs:

* A versioned, fully relocatable code image with an ABI/compiler-options/
  target/architecture/dependency fingerprint and a signed or otherwise
  integrity-validated immutable import manifest.
* Read-only memory-mapped image pages (\`mmap\` / suitable platform equivalent)
  mapped into many actor worker processes, or sealed immutable \`memfd\`
  mappings on supported Linux kernels; executable pages use RX, never RWX.
* Per-isolate mutable GOT/PLT, dispatch/inline caches, import-binding tables,
  static mutable slots, GC roots and thread/continuation state. Relocations
  must never dirty a page intended to be physically shared.
* No guest-visible raw mapping handles, writable alias, Java \`Source\` object,
  host classloader, pointer-based callable, or capability-bearing relocation.
* Old code generations pinned while any actor or continuation executes them;
  new spawns bind to the new version only at an explicit safe boundary.
  Retire after the final execution/import lease and resource finalizer.

Physical page reuse and memory safety must be demonstrated by native
integration tests, not inferred from \`Source\` identity, JIT caching, shared
JVM heap, or ordinary read-only Java wrappers. For \`UNTRUSTED\`, do not turn
off Graal sandboxing to achieve cache reuse; keep code-mapping and
capability policy under the trusted loader.

## Acceptance gates

1. Run 1,000 shared, 1,000 private and 1,000 untrusted actors on identical
   imports: prove identical immutable image identity and **distinct mutable
   actor/module initialization state**.
2. Prove class/actor/module sibling and qualified symbol reads fail without
   explicit import at both compile time and runtime. A real explicit import
   must remain usable without copying the imported executable definition.
3. Concurrency test acquire/release, hot reload generation pinning,
   failed initialization/close, cancellation while imports execute, and
   registry eviction after last owner retires.
4. Validate code-image digest mismatch rejection, source identity separation,
   per-actor capability rechecking, and no cross-sandbox object/closure escape.
5. Native workers: compare physical shared/private RSS/PSS across process
   counts and inspect memory mappings for shared RX/RO and owner-private RW.
   Require **one physical code image per version** (modulo OS page caching),
   not one physical copy per worker process.
6. Run exact-head Java/GraalVM CI; if source organization cannot schedule
   runners, use an exact Git-tree/blob-verified mirror in a funded test org.
   Do not merge this stack on zero-job allocation failures or doc-only proofs.

**Current status:** process-local immutable \`Source\` identity reuse is
implemented in this draft stack. Parsed/compiled definition sharing, strict
qualified lexical isolation, actor-local \`on_start\`/source \`spawn\`, and
read-only physical code mappings remain open engineering gates.

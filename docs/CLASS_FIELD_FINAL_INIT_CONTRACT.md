# Canonical concrete field declarations (pre-1.0 migration)

Design decision: a concrete class or named struct field has a binding-slot
capability independent of the referent's mutable-access capability.

| Canonical field | Reassign member slot | Mutate referent through member |
| --- | --- | --- |
| `T x: expr;` | yes, with mutable owner | no |
| `mut T x: expr;` | yes, with mutable owner | yes, if ownership allows |
| `final T x: expr;` | no | no |
| `final mut T x: expr;` | no | yes, if ownership allows |

`const` on a concrete member is a compatibility alias for `final`.
`val` / `const mut` alias `final mut`; `let` aliases the unqualified
slot. Canonical storage/member initializers use `:`, while local binding
initializers keep their existing `=` syntax.

`final` describes slot rebinding, **not** deep freezing, uniqueness, safe
cross-actor sharing, data-race freedom, or mutable-borrow exemption. A mutable
receiver/owner and the appropriate referent capability are both necessary for
deep mutation. An inherited final slot cannot be overridden to become writable.

## Initialization model

The intended final contract requires *all* final fields to have an explicit
initializer expression at their declaration. The constructor body may validate
parameters but must not perform final-field initialization, even once.
Construction must not expose partly initialized objects. Field expressions
execute in deterministic declaration order, including failure cleanup.

Constructor parameters in field initializers (for example
`final int n: input;`) are a **separate** compiler/runtime capability and
must not be accepted without type checking, evaluation order, and failure
cleanup verified on both backends. No synthesized positional initialization
is permitted in the final pre-1.0 contract.

Class fields are distinct from Oreslang actor state (fixed-key `state { ... }`
accessed only through the sealed actor state capability). Traits declare
contracts, not implicitly allocated instance storage. Closed inferred
structural values remain deeply readonly under their existing type contract.

## Migration and verification

The existing Java implementation permits some legacy `val` constructor
initialization and synthesized positional arguments. This migration does not
remove those behaviors by silently rewriting compiled code; separate breaking
changes must update the fixtures, compiler, runtime, and native parity gates.

Reject invalid field assignments and destructive takes **before** LLVM IR
emission; run JVM type checker + interpreter/AOT/hybrid as well as LLVM
native execution, ownership and sanitizer tests. The formatter must be
idempotent and never touch local `let`/`val`/`const` declarations.

## Implementation slice in this PR

Java parser recognizes the four typed class member declaration forms and
rejects a missing initializer for explicitly spelled `final`. Constructor
parameter initializers, fully strict alias treatment and constructor-body
final-assignment removal remain unresolved.

## Current Java implementation status (October 2026)

Java now type-checks class field initializer access to explicit constructor
parameters for a limited value-scalar subset: int, bool, float, string.
Reference, borrowed, generic, and structural parameters and the implicit
arguments tuple are not available to field initialization until the
ownership checker proves safe transfer. Parent initializers do not inherit
child constructor parameters. The runtime binds permitted values before
declaration-site initialization and executes the constructor body afterward.

The final and const aliases require declaration-site initialization.
Positional fallback construction cannot replace initialized fixed members;
the generic-inference and runtime paths enforce the same rule. Initialized
fixed members cannot be rewritten during construction.

Legacy uninitialized val constructor filling and some positional field
construction remain as explicit migration compatibility. Native LLVM does
not yet support constructor-parameter initializers. Real Maven, AOT/hybrid,
LLVM, sanitizer, and formatter results are still required before merging.

## Audit hardening: initializer execution boundary

Java does not bind self inside ordinary class field initializers.
An unfinished object is not a legal initializer value. Initializer
parameter frames contain copy-valued scalars only and carry no
constructor-write authority.

The ownership checker now visits ordinary class field initializer
expressions, including borrowed results and mutex guard storage.
The type checker rejects directly nested await, channel/select and
other suspension operations during field initialization.

Indirect calls with transitive effects, failed-initializer cleanup,
and non-scalar transfer proofs still need end-to-end verification.

## Constructor self-transfer hardening

The ownership checker rejects consuming constructor self as an ordinary
value before object publication, including a local alias that would retain
the incomplete receiver. Ordinary self.field accesses remain legal for
legacy constructor filling. Constructor method dispatch and indirect
callbacks still require broader transitive escape analysis; they are not
proven safe by this targeted check. All new code remains unverified by a
running Maven/GraalVM job.

## Follow-up construction escape audit

The ownership checker now treats constructor self as a nonpublishable
capability. It rejects direct self method dispatch, direct passing of self
to callable/intrinsic arguments, early self borrows (unary and rt borrow),
and rt copy/share/take of self. These checks protect even call paths that
inspect arguments without consuming them, including logging helpers.
Self field initialization remains available only under the legacy
constructor-fill rules.

This is intentionally a bounded, fail-closed static audit, not proof that
all transitive effects and cleanup cases are safe. Actual Java/GraalVM
execution evidence remains required.


## Executed CI regression triage (2026-10-13)

The exact older Java tree at commit 2702515 ran 1,831 Maven tests:
9 failures and 1 error in fast CI; JVM profile showed the same baseline
constructor/type/visibility regressions plus a deadline-reservation race.
The updated source has NOT passed the full Maven/AOT/hybrid suite.

The initializer admission now admits a statically resolved zero-argument
function or static method only when the full body is a single return of a
literal or closed scalar arithmetic expression. Synchronous declaration and
name resolution must still hold; dynamic/parameterized calls, mutation,
suspension and arbitrary constructor effects remain rejected. An existing
zero-argument built-in Array initializer is admitted as the preexisting
collection-construction form. Its native owned-resource failure behavior
remains an explicit separate verification target.

Tests now use pub for fields accessed by code outside the declaring class,
including the source-identical Java/LLVM scalar constructor guest. The
private visibility rule has not been weakened.

Strict conformance inventory now registers exactly its active files.
Three previously orphaned native defer/try-finally programs were preserved
with identical Git blobs in contracts/v1/semantics/staging/, explicitly
outside the admitted comparator fixture set. The strict orphan check remains
unchanged; promotion requires real backend evidence and a cases.json entry.

The deadline-reservation stress test formerly observed its quiescence
callback latch before the TaskRunner finally-block released the semaphore.
It now waits for the actual permit restoration with a bounded deadline
and still fails on a persistent leak. This is a test synchronization fix,
not a change to runtime reservation or notification semantics.

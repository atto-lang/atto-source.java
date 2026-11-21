# AttoLang namespace contracts — staged Java multi-file proof v1

> **Status (2026-11-10): draft / NOT EXECUTED.**
> This is Java frontend source and authored tests, NOT a compiler-backed
> receipt, a released guest feature, or LLVM/.NET/Wasm/Forge parity.

## Vocabulary and canonical syntax

A **namespace** is a logical, flat source partition within one trusted
package/build input, potentially containing multiple `.atto` files.
It is neither a runtime module nor an actor and has no heap/owner rights.

Declare exactly one conformance header in the complete set:

```atto
// shop/contracts.atto
namespace shop conforms ShopApi;

define contract ShopApi for namespace as
  actor Cart;
  fnc tick(int count): int;
end
```

```atto
// shop/cart.atto
namespace shop;
pub define actor Cart;
```

```atto
// shop/tick.atto
namespace shop;
pub fnc tick(int count): int { return count; }
pub fnc main(): int { return 42; }
```

All three files must be included in one
`IncrementalCompiler.compile(Map<sourceUnitId, source>)`. A single-file
`TypeChecker.check` with a conformance header fails closed.

The namespace contract is **not** a module contract. A module cannot
`conforms ShopApi` when `ShopApi` is targeted `for namespace`. A namespace
cannot claim an ordinary module contract. The contract declaration is
allowed only at file root, never inside a module.

## Scope and algorithm

1. Parse each source file, retaining the list of namespace claims and the
   declaration's `namespaceContract` target bit. Annotation expansion
   preserves the claims. The namespace proof helper is **package-private**
   inside the Java compiler: ordinary callers cannot invoke it as a
   stand-alone certification API using fabricated partial ASTs.
2. Partition the **complete explicit compilation source set** by declared,
   flat namespace. Do not infer membership from physical directories,
   globbing, imports, external package labels, or a matching namespace
   string in a different compilation invocation.
3. Require at most **one** nonempty conformance header per namespace; reject
   repeated contracts and unsupported generic contract arguments.
4. Collect root declarations (the compiler's `__root__` scope) from every
   participating file. Explicit named modules are excluded from the
   namespace's root witness set.
5. Reject cross-file duplicate type names, callable name+arity slots,
   and ambiguous runtime value names. Public witnesses must be declared
   directly within this namespace, not imported.
6. Resolve each claimed `for namespace` contract to one file-root
   declaration in the same source set. The initial slice accepts
   **non-generic, non-inherited** contracts with only:
   - `actor A;`: one public, non-generic actor `A`, not a class, alias,
     private actor, or unlowered `define actor A as a;` binding.
   - `fnc f(T a): U;`: one public non-actor, non-generic, synchronous `fnc`
     with matching name, arity, return and parameter types/mutability.
7. Validate **before** choosing cached compilation units. On any failure,
   do not modify persistent incremental cache state.
8. Add conservative implicit dependencies among source files that share
   a contracted namespace, so exported ABI changes invalidate peer units.
   Private namespace contracts also participate in the internal ABI.
9. After successful complete-set validation, a source file's namespace
   claim can be discharged for the existing single-unit type checker;
   the checked program still retains its original namespace claims.
10. Do not certify these multi-file obligations with the existing v1
    **single-file export interface**. A future package-level export format
    must bind the complete source list and contract provenance.

## Fail-closed limitations

Actor-specific `implements` contracts and executable top-level
`define mtd (Actor a) method()` bodies require **separate** nominal
receiver resolution, static method witnesses, actor mailbox dispatch,
per-actor context authority, and cancellation/generation fencing. A
namespace contract does not grant that access. The current parser already
has staged static actor receiver requirements in #2776 and compact actor
declarations in #2780, but not cross-file executable receiver lowering.

The source-set boundary is one invocation of the caller's trusted
package/build system. Until a package-manifest identity is carried through
the compiler and native linker, **do not combine unrelated packages** in
one namespace compilation map. Matching namespace names across separate
invocations do not merge.

Generic namespace contracts, inherited namespace contracts, contract
imports from another package, namespace-level field requirements,
receiver-method requirements and structural/effect-typed function
signatures are deliberately unsupported, with admission errors.

## Verification receipts (required before readiness)

- JUnit: `NamespaceContractMultiFileTest`, existing module-contract
  hardening, actor contract tests, incremental graph tests.
- Portable fixture source set: `contracts/namespace-v1/shop/{contracts,
  cart,tick}.atto`, plus two negative replacements.
- Negative mutation runs: delete an actor or function source, repeat a
  header/actor/function slot, rename a namespace, substitute a private
  actor, apply a module-only contract, and vary a public method ABI.
- Real Java/Graal compiler on the exact committed PR head and root tree,
  with nonzero runner steps, source/fixture blob digests, checked guest
  main returns, explicit negative compiler diagnostics, independent review.
- Only after Java evidence, port and execute the same corpus on LLVM,
  .NET NativeAOT, Wasm, Forge/Cranelift. Parser-only or host-only
  simulation is not backend parity.
- `runner_id=0`, `steps=[]` or `steps=null` are runner-admission
  failures, not passing or failing tests. Source-tree transfers to
  other GitHub organizations require the already documented independent
  private-source carrier authorization and exact-head publisher checks.

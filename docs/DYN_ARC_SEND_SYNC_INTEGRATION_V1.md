# dyn / Arc / Send / Sync convergence (draft contract)

Status: compiler-only integration slice. Neither source `Arc<T>` nor native owned
`dyn` ABI is enabled by this branch. Keep all dependent PRs in draft until the
exact-source Java and LLVM conformance gates pass.

## Canonical compiler authority

Resolved semantic `Types.Type` is the authoritative input for `ThreadSafetyTraits`
(provenance: Java #1439). `Ast.TypeRef` and its capture/lifetime metadata feed
the resolution and ownership checker; a separate source-AST proof engine (#1447,
#1452) must converge into this checker rather than becoming a second independent
permission oracle. Avoid accepting guest-asserted `unsafe impl Send`/`Sync`.

For a known, immutable scalar or a fully resolved aggregate, derive traits
transitively; a writable list/record can be Send but does not become Sync by
element-wise derivation alone. A shared borrow `&T` is Send iff T is Sync; an
exclusive borrow `&mut T` can be Send iff T is Send. The borrow checker must
*separately* prove scope/outlives, exclusivity, physical quiescence, and
no cross-await guard escape. Trait derivation is not an ownership transfer.

**Erased views:** current `Types.Dyn` carries nominal interface witness but
no independently proved auto-trait bounds. Consequently *both* Send and Sync
return false for `dyn I`, `&dyn I`, and containers holding them, regardless of
how permissive the nominal proof callback is. Later `dyn I + Send + Sync`
requires a distinct compiler-minted, immutable witness backed by the concrete
implementation and a verified runtime vtable/ownership ABI. Do not infer these
bounds from the interface's name or declaration alone.

**Arc, Weak and locks:** current guest runtime has no proven intrinsic lowering.
The auto-trait evaluator deliberately denies `Arc<T>`, `Weak<T>`,
`Mutex<T>`, `RwLock<T>`, and all lock guards even if an arbitrary user class
has the same name. A future compiler-minted intrinsic descriptor (not a string
comparison) may implement these Rust-style conditions:

| Verified runtime intrinsic | Send | Sync |
| --- | --- | --- |
| Arc<T>, Weak<T> | T: Send + Sync | T: Send + Sync |
| Mutex<T> | T: Send | T: Send |
| RwLock<T> | T: Send | T: Send + Sync |
| Rc<T> | no | no |
| Cell<T>, RefCell<T> | T: Send | no |

The intrinsic proof also requires allocation ownership, atomic memory ordering,
strong/weak overflow and destruction, reclamation fences, borrow/guard admission,
and runtime capability-domain authorization. Actor `Sendable`, wire
`Serializable`, and shared-memory permission are independent from Task Send
and Sync. The sole sealed actor `state { ... }` remains inaccessible to Arc.

## Compiler and runtime integration order

1. Merge or rebase Java dyn #1421 after Java JDK25/GraalVM verification.
2. Reconcile `ThreadSafetyTraits` (#1439) with #1444's owned-Send checks and
   AST/capture inference from #1447/#1452. Keep one canonical semantic result.
3. Check `dyn` in every generic substitution, implicit conversion, Task
   capture, async boundary and actor transport. No `dyn I + Send + Sync` until
   source syntax, nominal proof, ABI and lifetime agreement are implemented.
4. Lower `alloc::Arc` and `alloc::Weak` behind a trusted compiler intrinsic;
   use exact-once deterministic native drops and separately modeled JVM
   ownership. No unverified sharing into private actor heaps.
5. Add scheduler-integrated `Mutex` and `RwLock` loans, with bounded,
   nonparking acquisition and physical-quiescence cancellation barriers.
6. Implement Java/LLVM borrowed dyn vtables and only then owned
   `Arc<dyn I + Send + Sync>`, with generation-pinned hot reload.

## Positive and negative source tests

- Scalars, immutable tuples, readonly records and owned Send aggregates.
- Writable List/Record is Send but not Sync; `&List<T>` is not Send without
  independent Sync evidence.
- Unknown, unbound generic, cyclic type and nominal shadowing fail closed.
- `dyn I`, nested `Option<dyn I>`, `Result<_,dyn I>`, `Arc<dyn I>`,
  `&dyn I` remain non-Send and non-Sync in phase 1.
- A user-defined `class Arc` or `class RwLock` must not mint an intrinsic.
- Detached Task cannot own borrowed stack storage; scoped Task cancellation
  must quiesce before moving/dropping allocations, even if Future was canceled.
- Async Channel owned-send admission must keep its existing positive behavior;
  actor messages do NOT inherit permission from Send/Sync.
- Native weak-upgrade versus final drop, refcount overflow, relocation,
  stale-vtable generation, cancellation, and ASan/UBSan/TSan stress.

## CI evidence and authorization

Use `bash scripts/test-dyn-interface-views.sh` for targeted JVM tests followed
by full JDK25 Maven/GraalVM and LLVM guest conformance. Source-org CI currently
fails before steps for dyn #1421. Do not call static source checks or mocked
commands a JUnit success. The protected private CI allowlist remains empty
pending reviewed security PR #1430; no cross-org source transfer is authorized.

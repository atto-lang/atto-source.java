# Java namespace import / verified package-local assembly — phase 1

Status: **parser plus source-only namespace graph; not executable package namespace imports**.

## Accepted source form

    import ns a from 'pkg:foo';

"ns" is contextual in import position. The AST selector is NAMESPACE, and
namespace a; remains the flat source-header declaration. This selector does
not merge define module a, file imports, or public symbols from any other package.

The Java PackageNamespaceLinker.link(programs, sourceOwners, pinnedAliases)
API validates and aggregates only simple public, root-level, non-generic,
non-actor fnc declarations at this stage. Identity:
(PackageInstanceId, namespaceName). Explicit sourceOwners cover EXACTLY the
provided programs. Every namespace import selects an explicitly pinned target
package instance and exact namespace, without fallback to directory, CWD or
the source-header text. Cross-package identity never merges, even when names
match or different package versions share the same name. The linker refuses
unsupported public class/type/actor namespace exports until checked nominal
identity and ABI lowering are available.

**Boundary:** sourceOwners and pinnedAliases MUST come from a separately
verified manifest and lockfile resolver. These maps are assertions, not
cryptographic package verification; do not construct them by trusting a
namespace header or the filesystem's apparent directory grouping. The linker
does not itself validate symlink/hardlink canonical identity or signatures,
and it does not typecheck function bodies.

The pre-existing IncrementalCompiler / LinkedProgramRunner / standalone
Evaluator currently use file-unit imports and **reject import ns** explicitly.
They must not incorrectly evaluate pkg:foo as a filesystem path, resolve
same-spelling namespaces from another package, or return Unknown at runtime.
This PR does not change that until actual runtime namespace facade/call-site
lowering and verified dependency source inventory are implemented.

## Next acceptance gates

1. Wire a verified, pinned package manifest/lockfile inventory to the linker
   (including explicit source ownership and canonical path validation).
2. Typecheck qualified namespace member calls against the linked exports,
   checked effects and overload resolution, and reject missing/private names.
3. Lower runtime namespace interfaces to callable cross-unit handles; run
   the ten-source / three-namespace consumer to **43** on Java/Graal, not a
   source-free mock.
4. Ensure correct once-only initialization, hot reload/ABI invalidation,
   module isolation and capability separation.
5. Repeat on LLVM, .NET, Forge, Wasm and JS ESM, including explicit-only
   public exports and source maps.
6. Prove actual CI execution: a job with steps:null is not test evidence.

Contract: oreslang-common-source PR #189. Related Java PRs: #2169,
#2066 and #1315 need reconciliation.

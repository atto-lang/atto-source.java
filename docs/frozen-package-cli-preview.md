# Frozen package CLI preview

This opt-in lane is a bounded scalar compiler candidate, **not** the general package
manager or full AttoLang source linker. The default compiler path remains unchanged.
An operator supplies each ZIP and independently trusted lock SHA-256, expected registry
name and version. Never compute the production trust pin from an untrusted archive.

```sh
atto-java check-frozen main.atto --package provider.zip @org/project/package 1.0.0 "$TRUSTED_LOCK_SHA256"
atto-java run-frozen main.atto --package provider.zip @org/project/package 1.0.0 "$TRUSTED_LOCK_SHA256"
```

Here `atto-java` denotes the existing Java launcher (dev.oreslang.launcher.OresMain). The run command returns the guest's integer exit status in the restricted AOT execution profile, not a standalone native-image guest receipt.

Supply additional packages using repeated `--package ZIP NAME VERSION TRUSTED_LOCK_SHA256`.
Only the explicit canonical .atto consumer and the in-memory source inventory are admitted;
there is no archive extraction, ambient source discovery, package fetching, dependency installation,
host permission override, or runtime alias object.

Archive contents use the existing bounded snapshot profile: exactly .atto.pkg.toml,
.atto.pkg.lock, and pinned .atto source entries. No directory entries, traversal,
duplicate names, unknown entries or unpinned source files. Limits: 8 MiB compressed
and expanded per archive, 66 entries total (64 source files plus manifest/lock),
8 KiB manifest, 32 KiB lock, 1 MiB per source, 64 KiB consumer, 16 supplied packages.
The transport is an implementation preview, not a normative registry archive format.
File paths are explicitly supplied by a trusted host operator, not by guest code;
the digest checks authenticate the bytes actually consumed and not the host directory.

```atto
import pkg '@org/project/package' as p;

pub fnc main() -> int {
    return p.network.answer(40);
}
```

The pinned provider can implement `pub fnc answer(int x) -> int { return x + 2; }`
under `use namespace network;`. Expected guest result: 42.

Still outside this bounded profile: general CLI source linking, transitive provider
dependencies, arbitrary declarations and control flow, and multi-file namespaces.
CI receipts must show real execution on the exact PR head/tree; queued jobs do not
establish compiler or native execution parity.

## Dependency closure preview

The candidate manifest can declare exact dependency versions; the corresponding lock table pins each dependency's complete lock SHA-256. For example, append to the manifest:

```toml
[dependencies]
"@org/project/b" = "1.0.0"
```

Append to the lock after `[files]`:

```toml
[dependencies]
"@org/project/b" = "<actual lowercase 64-digit dependency lock SHA-256>"
```

The example hash is a placeholder, not an admitted digest. Every supplied archive still requires an independent trusted pin. The manifest and lock dependency key sets must match exactly. Before any provider parsing, verify all supplied packages, reject absent dependencies, conflicting versions/lock digests, duplicates and cycles, and derive deterministic dependency-first order. A package may resolve only a directly declared edge, not a dependency merely reachable through another package. This profile permits at most 16 packages and 128 aggregate edges; unused supplied snapshots are checked too. Cycle diagnostics precede edge digest mismatch checks on structurally cyclic malformed input. These tables are an implementation preview, not yet a normative registry lock format.

## Direct dependency source calls (bounded candidate)

A provider may use a single explicit typed import from an exported subpath of a
directly declared and pinned dependency:

```atto
use namespace network;
import fnc answer as next from pkg '@org/project/b/api';

pub fnc answer(int x) -> int {
    return next(x) + 1;
}
```

Every original provider source is parsed with the strict frontend after its bytes
have been verified. The linker processes the dependency-first order, validates
the selected public function against its provider's source-owned export inventory,
and lowers only the checked leading import declarations to compiler-owned relative
unit mappings. It preserves provider bodies and rechecks the declaration AST after
lowering. The regular strict compiler checks the complete linked source graph.
The restricted guest loader registers all per-unit edges before link/init/main.
Imports are not re-exports; transitive reachability does not grant direct authority.
No ambient filesystem fallback. This conservative candidate eagerly checks all
supplied providers (maximum 16 namespace providers and 64 methods).

CI adds a three-package guest expected to return 42 under both snapshot inventory
orders, plus five denied edges. These are test candidates, not executed receipts.
The lane is still bounded: grouped and namespace imports inside providers,
relative source dependencies, multi-file namespaces, and general CLI linking
remain outside this profile.

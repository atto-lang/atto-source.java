# Oreslang core / std boundary

Oreslang exposes exactly two public library namespaces.

| Namespace | Contract |
| --- | --- |
| `ores.core` | Freestanding/no-OS. May depend only on `ores.core`. |
| `ores.std` | Hosted/platform facilities. May depend on `ores.core` or `ores.std`. |

Application source is not a third library. Allocation and actor execution are
implementation requirements, not public tiers: a fixed allocator or bounded
actor executor can support `ores.core` on bare metal, while an OS-backed
allocator, scheduler, filesystem, socket, process, or similar adapter belongs
under `ores.std`.

## Compiler rules

1. Resolve canonical library names before project and ambient filesystem search.
2. Reject `ores.core -> ores.std`, Java, project, and ambient import edges.
3. Keep compiler-owned source in closed registries so application files cannot
   shadow trusted modules.
4. Preserve the legacy `std/*` spelling only as an alias for `ores.std/*`
   while callers migrate.
5. A backend may advertise a `core` build only after it can produce and
   validate a genuinely freestanding artifact. Java/Graal therefore rejects
   `library_profile = "core"` before compilation today.

LLVM/native is the path that must eventually prove a real no-OS artifact with
freestanding linking, forbidden-symbol/import checks, and target execution.
Managed backends may execute `ores.core` semantics without claiming that the
host VM itself is freestanding.

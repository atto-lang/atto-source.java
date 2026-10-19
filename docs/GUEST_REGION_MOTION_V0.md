# Oreslang guest Region<Motion> V0

**Status:** narrow Java-only compiled guest execution, not general-purpose `Region<T>` nor actor/Task region safety.

## Compiler-known ABI

Guest code uses `Region.new<Motion>(capacity:int, tile:int, residencyMask:int)`. `Motion` is currently a compiler-owned fixed-width scalar shape `(x:float, y:float, vx:float, vy:float)`; other payload types and user-defined records are statically rejected. This temporary V0 name is reserved, not user-overridable. A general structural/nominal `Region<T>` needs a separate type-layout proof.

Residency bits: **1 AoS, 2 SoA, 4 AoSoA**, mask 1..7. `set_layout(0|1|2)` selects AoS/SoA/AoSoA only if resident, reconciles a stale buffer, and publishes a new authoritative epoch. A second independently writable representation is never created.

Region methods (V0): `insert(x,y,vx,vy): RegionHandle`, `get_x(handle): float`, `get_y(handle): float`, `set(handle,x,y,vx,vy): void`, `erase_swap(handle): void`, `size():int`, `epoch():int`, `set_layout(ordinal:int):void`, `close():void`. Handles carry a private store ID, slot and generation; they are immutable Copy values, never permissions for foreign-region access. Region itself remains move-only, and no raw backing array is exposed.

Interpreter-owned construction is limited to **1..4096 rows** and **1 MiB checked primitive payload per Region**. Input numeric fields must be finite, layout masks/ordinals must be checked, and invalid/stale/foreign handles fail. These are **per-instance** bounds and not a complete tenant quota or context-global DoS proof.

## Executable guest fixture

```ores
pub fnc main(): void {
    let region = Region.new<Motion>(3, 2, 3);
    let row = region.insert(10.0, 20.0, 1.0, 2.0);
    stdio.stdout.write(region.get_x(row));
    region.set_layout(1);
    region.set(row, 15.0, 25.0, 1.0, 2.0);
    region.set_layout(0);
    stdio.stdout.write(region.get_y(row));
    region.erase_swap(row);
    region.close();
    return;
}
```

`RegionMotionGuestV0Test` runs the genuine Java parser, TypeChecker, OwnershipChecker and Truffle evaluator for each of the seven resident sets; rejects unsupported generic types, invalid constructor arity/scalar types, stale and foreign handles, closed storage, absent layouts and oversized requests. Host-Java tri-layout and bounded reference-model tests remain independent gates.

## Explicitly NOT supported

- Arbitrary user-defined class/struct/record `T`, generic region construction or schema-driven field layouts.
- Region loans into `task {}`, Task cancellation/liveness, ActorGroup capabilities, actor-private arenas, concurrent cross-layout writers, or Send/Sync permission proofs.
- LLVM guest compiler/runtime parity, real zero-GC native region storage or production AOT.
- A hard context/tenant aggregate quota or ownership-effect verification for first-class method references.

Never generalize V0 guest success into proof of complete ECS concurrency, unrestricted `Region<T>` or the native backend.

# Canonical AttoLang Java library admission (v1 bridge)

The public library names are `atto.core`, `atto.alloc`, `atto.std` and
optional `atto.runtime.actor`. Java/Truffle currently has closed compiler-owned
Core and Std resource registries; Alloc and the actor **module namespaces** are
reserved and reject ambient filesystem/ORELANG_PATH fallback until their
provider/ownership/linker contracts are implemented.

This PR admits `atto.core/numeric[.atto]` and `atto.std/testing[.atto]`
alongside legacy `ores.core`, `ores.std` and `std` imports. Java deliberately
retains historical internal `@ores.core/*.ores` and `@std/*.ores` unit IDs
and packaged `.ores` resource names so old and new spelling resolve to **one**
module and one initialization identity, with no runtime ABI break. Migrating the
packaged library source bodies to canonical `.atto` is a separate byte-identity
and multi-backend migration, not something this alias PR falsely claims.

The new JUnit test must run the real Java/Truffle parser, linker and guest to
output `42`, and must check alias equivalence and namespace/traversal denials.
The workflow verifies exact PR SHA and Git tree first. A zero-step workflow
never proves compiler execution. This is **not** a no-libc Java proof, actor
runtime proof, nor evidence of cross-backend guest parity.

# Compiler-owned AttoLang libraries (Java/GraalVM)

Layered `core`/`alloc`/`std` units per
[atto-spec core-alloc-std-libraries-v1](https://github.com/atto-lang/atto-spec/blob/main/language/core-alloc-std-libraries-v1.md),
packaged as resources under `dev/oreslang/lib/` and imported with

```atto
import module num from "core/num";
import module arena from "alloc/arena";
import module path from "std/path";
```

| Layer   | Units |
| ------- | ----- |
| `core`  | `num`, `cmp`, `option`, `result`, `mem` |
| `alloc` | `arena` |
| `std`   | `io`, `path` (POSIX/Windows variant chosen by `--target-os=` / `--platform=`, else the host OS) |

* `core/`, `alloc/` and `atto.*` roots are reserved and fail closed for unknown
  units. On the shared legacy roots (`std/`, `ores.core/`, `ores.std/`) only v1
  unit names select this registry; every existing legacy module keeps working.
* Library units may import only their own or a lower layer.
* `.atto` units run under the checked-i64 profile; imports of library units and
  explicit `.atto` files are admitted in that profile.
* Calls into imported modules are typed from the provider's checked public
  signatures when every public function has a concrete signature.

`src/test/java/dev/oreslang/AttoLibrariesV1Test.java` runs the shared
`tests/atto_libraries` corpus through the real launcher (15/15), and
`tests/atto_libraries_v1.py --backend java --launcher <atto>` does the same from
the command line.

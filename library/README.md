# Compiler-owned AttoLang libraries (Java/GraalVM)

Layered `core`/`alloc`/`std` units per
[atto-spec core-alloc-std-libraries-v1](https://github.com/atto-lang/atto-spec/blob/main/language/core-alloc-std-libraries-v1.md),
packaged as resources under `dev/oreslang/lib/` and imported with

```atto
import module num from "atto.core/num";
import module arena from "atto.alloc/arena";
import module path from "atto.std/path";
```

| Layer   | Units |
| ------- | ----- |
| `core`  | `num`, `cmp`, `option`, `result`, `mem` |
| `alloc` | `arena` |
| `std`   | `io`, `path` (POSIX/Windows variant chosen by `--target-os=` / `--platform=`, else the host OS) |

* `atto.<layer>/<unit>` is canonical; `<layer>/<unit>` is accepted shorthand.
  `ores.*` spellings never select a v1 unit. `atto.core/<x>` and `atto.std/<x>`
  also reach every pre-v1 legacy module (e.g. `atto.std/assert`); the old
  `ores.*`/`std/` legacy spellings keep working for those modules only.
* Unknown units under `atto.*`, `core/` and `alloc/` fail closed.
* Library units may import only their own or a lower layer.
* `.atto` units run under the checked-i64 profile; imports of library units and
  explicit `.atto` files are admitted in that profile.
* Calls into imported modules are typed from the provider's checked public
  signatures when every public function has a concrete signature.

`src/test/java/dev/oreslang/AttoLibrariesV1Test.java` runs the shared
`tests/atto_libraries` corpus through the real launcher (15/15), and
`tests/atto_libraries_v1.py --backend java --launcher <atto>` does the same from
the command line.

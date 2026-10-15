# Sealed class/interface hierarchies — first source admission slice

**Status:** experimental draft; Java parser and type-checker admission only.
Do **not** treat this as implemented source/runtime parity with LLVM, an
exhaustive-match optimization, or a hot-reload linking guarantee.

## Syntax

```oreslang
define sealed class WebEvent permits Click, Scroll as
end

define final class Click from WebEvent as
end

define final class Scroll extends WebEvent as
end
```

`from` in a class header is an alias for the existing `extends` clause.
`sealed`, `final` and `permits` are contextual identifiers, not globally
reserved lexer keywords. A sealed interface accepts final implementing classes
or sealed subinterfaces. The explicit `permits` list can be omitted when all
direct children are known in the same compilation unit and module.

## Admission rules in this PR

- Only ordinary class/interface declarations participate (no sealed actors
  or sealed module contracts).
- A sealed hierarchy requires at least one *direct* child in the same
  compilation unit and module.
- Explicit permit names must be unique, must be real direct children, and
  cannot authorize an unrelated type. Nonlisted direct children are rejected.
- A direct child must be `final` or itself `sealed`; no `non_sealed` escape.
- `final` classes cannot be extended and cannot be abstract or sealed.
- A linked/imported sealed parent is rejected until complete metadata can
  be proved at the link boundary; it is never silently opened.
- These rules do not relax actor isolation, ownership/borrowing, sendability,
  immutable sharing, or capability checks.

## Outstanding work / release gates

1. Teach the type checker to prove exhaustive matches over *closed* nominal
   subtype trees with coverage diagnostics for missing/unreachable cases.
2. Store canonical type IDs, permitted subtype graph, module identity and
   generation version in runtime/linker metadata, validating incoming classes
   before initialization and after hot reload.
3. Preserve sealing across imported module descriptors and binary compilation
   units, rejecting forged or stale metadata, and test separate compilation.
4. Implement LLVM source/type/IR parity with the same failures and source
   fixtures; keep native sealed syntax rejected with no IR until that lands.
5. Pass exact-head Java JUnit/Maven, native CTest, and differential parity
   gates before promoting from draft or merging.

`enum` remains preferable for pure closed sets of tagged data values;
`sealed class` is for polymorphism and shared implementation/behavior.

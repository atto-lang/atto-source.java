# Oreslang privacy at external observation boundaries

This is a security-oriented behavior contract, not evidence that every backend
implements every operation. The source language defaults declarations and named
modules to `private`. An explicitly `pub` member inside a private named module
is still not an external export. The synthetic file-root may export its own
`pub` declarations but is not itself an importable module.

## Different authority domains

| Operation | Visibility contract |
| --- | --- |
| Lexical class field access / object destructuring | Private fields are available only to the declaring class's lexical authority; subclasses do not inherit that authority. |
| Structural typing and pattern matching | Use public, inherited-public field/method shape. Private fields do not establish width-subtyping or pattern success, even if names and runtime types match. |
| Structural record return validation | When a class instance is checked as a public record result, inspect only public fields; a private slot must not satisfy the record. |
| Unscoped/dynamic object destructuring | No anonymous authority. Fail closed without a validated lexical access context or explicit public structural projection. |
| Default object stringification/logging | Display public fields only, including through inheritance. Do not display private field names or values. |
| External module/class/function imports | Both the named container and member must be public. Root public declarations are an explicit exception for container eligibility. |
| Host reflection, dynamic member APIs and interop | No implicit authority escalation. Any new reflection bridge must enforce the same owner/visibility rule as lexical member access. |
| External JSON/MessagePack/protobuf serialization | Not implemented as a general object-export API by this PR. A future codec must be explicit about exported fields, custom serializers, schema version, actor restrictions, cycles, capabilities and redaction; private state must not silently become a public map. |
| Internal actor isolation/copy | Preserve class ownership and visibility metadata for private internal state; transport may need private data for correct behavior. Internal actor copies are NOT equivalent to external serialization/export. |

**Important:** A private field is not automatically a secret in every domain:
authorized class code can intentionally disclose its value through a public
method, and explicitly supplied constructor arguments or public serializing
methods may encode application decisions. This contract prevents accidental
exposure through generic language mechanisms, not deliberate public API outputs.

## Required executable gates

- Compile-time: reject unauthorized class destructuring, private structural
  witnesses, private constructors of imported classes, and private positional
  arguments in synthesized constructors.
- Runtime: verify dynamic/wildcard paths, inherited private field boundaries,
  named and inline structural patterns, and public record return-shaping.
- Formatter: normalization may not remove conflicting modifiers and turn
  malformed or rejected input into an exported declaration.
- LLVM: assert internal linkage for private functions, and for all functions
  in private named modules; no public-module export until a checked ABI exists.
- Java and LLVM supported-subset fixture comparison: acceptance, diagnostics
  and symbol visibility must agree where both support the construct.
- Full suites require real executed CI steps. GitHub Actions zero-step failures
  provide no compiler or runtime verification evidence.

Relevant Java tests:
`PrivateVisibilityTest`, `ModuleVisibilityRuntimeTest`,
`ModuleVisibilityOptimizationTest`, `ImportedConstructorPrivacyTest`,
`PrivateObjectDisplayTest` and `StructuralPrivacyBoundaryTest`.

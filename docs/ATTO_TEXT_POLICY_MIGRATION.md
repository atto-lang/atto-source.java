# Canonical AttoLang text validation — migration slice

Date: 2026-11-04. This is a source and authored-test change, **not**
an executed compiler or multi-backend parity receipt.

## Semantic owner

`stdlib/text.atto` contains the portable public identifier and route-segment
validation logic that was previously expressed as two regular expressions in
`OresEvalRootNode.member(String)`. Its `is_identifier` rule permits
`[A-Za-z_][A-Za-z0-9_]*`. Its `is_route_segment` rule permits
`[A-Za-z0-9_-]+` or a bracketed identifier `[name]`. These are ASCII
protocol identifiers, **not** full Unicode identifiers or a URL sanitizer.

Java adds one narrowly typed `String.codepoints() -> List<int>` storage
primitive. It returns a fresh bounded snapshot (maximum 65,536 Unicode scalar
values), rejecting unpaired UTF-16 surrogates and avoiding UTF-16 indexing
leakage into portable policy. No platform-specific regex is used by the .atto
implementation. The legacy Java regex member methods remain a compatibility
path during migration, and are compared on representative valid/invalid inputs
by `stdlib-parity/text-policy.atto`; they are not yet deleted.

This is an explicit behavior limit: strings exceeding 65,536 code points
are rejected by the new primitive, even if the historical Java regex path
could process larger inputs. Backend parity requires reproducing that quota
and failure behavior, not silently truncating or widening it.

## Review gates

- Java/GraalVM: the native .atto module must be parsed, checked, linked,
  executed and compared against historical regex results at exact head.
- Negative tests: empty names, NUL, Unicode non-ASCII, supplementary scalar,
  delimiters, bracket validity, 65,536 vs 65,537, malformed host Unicode,
  source shadowing and forbidden packaged host interop.
- LLVM, .NET NativeAOT, Wasm, Forge: `codepoints` must implement the same
  Unicode-scalar, quota and error contract. Unsupported targets report
  NOT_EXECUTED/UNSUPPORTED, never guest-pass parity.
- Only after stepful equivalent compiled-guest receipts on supporting backends
  and dependent call-site migration should the two Java regex methods
  be removed or made explicitly deprecated compatibility entry points.
- No private compiler source mirrors to other GitHub organizations
  without independent governance approval; `steps=null` is runner admission,
  not a compiler test result.

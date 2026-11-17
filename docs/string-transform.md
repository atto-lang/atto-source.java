# Literal string replacement

`value.replace_literal(needle, replacement)` returns a new string with every
non-overlapping occurrence of the nonempty `needle` replaced from left to right.
Both arguments must be strings. An empty needle is a runtime error.

The operation is literal: regex metacharacters, dollar signs, and backslashes
have no special meaning. Inserted replacement text is not searched again.
Unmatched text is preserved without normalization, including supplementary
Unicode characters and embedded NUL (U+0000). Oreslang strings are
length-bearing values: NUL is data, never a terminator. The Java/Graal carrier
uses the JVM String's explicit logical extent and must never infer an Oreslang
string boundary by scanning for a zero code unit. At UTF-8/native boundaries,
the complete value is encoded and accompanied by its exact byte length; no
backend may use C `strlen`/sentinel semantics for an Oreslang string. This
operation does not introduce a character-indexing contract.

The cross-backend String ABI v1 contract is defined in
`oreslang-common-source/docs/STRING_ABI_V1.md`. C interop is deliberately
separate: the internal `Utf8StringAbi.CString` adapter owns a trailing NUL,
records the payload byte length excluding that terminator, and rejects embedded
NUL rather than truncating the Oreslang value. There is no implicit
`string -> CString` conversion.

This is a storage/runtime primitive, available without Java interop or host
access. The Truffle interpreter adapts its existing string storage; libraries
implement context-sensitive policy in Oreslang. It is not an HTML, JavaScript,
CSS, or URL sanitizer.

For HTML escaping, library code can replace ampersands first, followed by the
other delimiters. Each call returns a new immutable string. A native UTF-8
builder and streaming writer remain separate work under #246; this primitive
does not claim allocation-free rendering or complete that performance work.

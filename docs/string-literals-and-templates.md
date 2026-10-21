# Oreslang quoted strings and templates

## Literal delimiters

A single-quoted or double-quoted string ends only at its matching unescaped
delimiter. The other quote is ordinary data. A backtick template preserves
both quote characters as data:

| `"abc"` | `abc` |
| `"'abc'"` | `'abc'` |
| `'"abc"'` | `"abc"` |
| `` `'"abc"'` `` | `'"abc"'` |
| `'"abc"'` | "abc" |
| ``'"abc"'`` | '"abc"' |

The delimiter characters are not part of the value. Strings preserve spaces,
punctuation, embedded braces, and Unicode characters without quote conversion.

## Templates

Backticks support string interpolation. The syntax `${expression}` evaluates
a checked Oreslang expression at that position in the template, in source
order and exactly once. For the initial contract, every interpolation must
produce a string. There is no implicit conversion from int, bool, actor
references, or arbitrary objects, and no hidden method invocation.

For example:

```oreslang
const fnc foo = |string bar, string star| -> {
  return `${bar} is ${star}`;
};

fnc render(string bar, string star): string {
  return `${bar} is ${star}`;
}
```

Interpolation parsing balances braces and ignores braces occurring within
quoted strings, comments, and nested backtick templates. Empty and malformed
interpolations fail rather than being passed through as literal text. These
expressions go through the ordinary type and ownership checks.

Template escapes include newline, carriage return, tab, backslash, both quote characters,
Unicode `\uXXXX`, an escaped dollar sign (`\# Oreslang quoted strings and templates

## Literal delimiters

A single-quoted or double-quoted string ends only at its matching unescaped
delimiter. The other quote is ordinary data. A backtick template preserves
both quote characters as data:

| `"abc"` | `abc` |
| `"'abc'"` | `'abc'` |
| `'"abc"'` | `"abc"` |
| `` `'"abc"'` `` | `'"abc"'` |
| `'"abc"'` | "abc" |
| ``'"abc"'`` | '"abc"' |

The delimiter characters are not part of the value. Strings preserve spaces,
punctuation, embedded braces, and Unicode characters without quote conversion.

## Templates

Backticks support string interpolation. The syntax `${expression}` evaluates
a checked Oreslang expression at that position in the template, in source
order and exactly once. For the initial contract, every interpolation must
produce a string. There is no implicit conversion from int, bool, actor
references, or arbitrary objects, and no hidden method invocation.

For example:

```oreslang
fnc render(string bar, string star): string {
  return `${bar} is ${star}`;
}
```

Interpolation parsing balances braces and ignores braces occurring within
quoted strings, comments, and nested backtick templates. Empty and malformed
interpolations fail rather than being passed through as literal text. These
expressions go through the ordinary type and ownership checks.

), and an escaped backtick.
Invalid template escapes are rejected. An unescaped dollar sign not followed
by an opening brace is ordinary text.

**Implementation scope (October 2026):** Java/Graal parses and evaluates
single-line backtick templates via type-checked string concatenation. The
Rust formatter treats single-line templates as opaque to preserve bytes; it
currently rejects multiline string/template formatting. The LLVM scalar
frontend does not yet lower strings or template interpolation. Do not claim
backend parity until LLVM, WASM, and .NET pass equivalent executable tests.

Backticks are not computed struct-field names; those remain unsupported by the
static struct shape contract.

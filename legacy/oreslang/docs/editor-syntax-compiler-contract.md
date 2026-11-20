# Oreslang editor syntax contract: compiler observations

This focused regression verifies 69 compiler lexer/parser cases against
`src/main/java/dev/oreslang/parser/Lexer.java` and `Parser.java` at the **PR's
exact source head**, rather than testing a guessed editor grammar or merely
scanning files for keywords.

The matrix is `src/test/resources/editor-syntax-lexer.tsv`. Each line contains
an ID, UTF-8 source encoded in hex, lexer token-type names (or !error fragment),
parser oracle P(accept), N(reject), S(skip), and explanatory note. The harness
asserts lexer output and separately asserts parser outcome. The corpus is
compiler-internal and should remain in this private repository; public editor
repositories should consume only a separately reviewed, non-sensitive language
contract and their own highlighting tests.

Important: parser acceptance is not typechecking, lowering, guest compilation,
or execution. The matrix records the current implementation, including
legacy differences from proposed syntax: these tests must be reviewed before
compiler language design changes and are **not** permission to preserve
legacy behavior indefinitely.

Release gates: executed (nonzero-step) exact-head JUnit evidence, independent
review, editor TextMate and Sublime integration tests, branch protection.

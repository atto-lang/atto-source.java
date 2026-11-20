# Private CI carrier candidate — Java PR #1050 lambdas runner

Status: **NON-AUTHORIZING / REVIEW ONLY**

Candidate repository: `ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-lambdas`  
Immutable repository ID recorded by the original review: `1412143323`.

This review record replaces the earlier proposed allowlist row. It does **not** modify `.github/private-ci-approved-repositories.txt`, does not modify the carrier contract, and does not authorize private-source export.

Historical source-free evidence recorded by the proposal:
- runner-capacity canary `38840451771`: success;
- GraalVM JDK25/Maven smoke `38840561373`: success;
- source-free JUnit/tomlj smoke `38840836008`: 3 tests passed.

The intended source target was Java PR #1050. Those historical source-free runs are capability evidence only, not compiler/runtime evidence for PR #1050 and not current authorization. Any future source-bearing use requires a new current authorization proposal with exact source-head/tree binding and fresh carrier validation.

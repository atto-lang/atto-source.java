# Java/GraalVM AttoLang library source / guest hardening

New JUnit cases in `AttoLibraryNamespaceResolutionTest` exercise canonical
and legacy suffix aliases, traversal/path-syntax and unknown-module negatives,
six actual compiler-linked numeric guest executions covering min/max/clamp,
hosted Std module ID compatibility, and ambient-user shadow files for reserved
`atto.alloc` and `atto.runtime.actor` module roots.

The guest checks call the actual `LinkedProgramRunner.validate` and
`LinkedProgramRunner.run` entry points, rather than a source-only parser
model. The negative shadow tests require the compiler-owned namespace root to
reject source substitution.

The profile/ownership transitive effects across all five backends are **not**
proven by this suite. Tests are authored but not executed under GitHub Actions
while source-owned runner admission is blocked; do not count as guest receipts.

## Historical std/* namespace security

The compatibility import prefix `std/` is now included in the same
compiler-owned *reserved root* classification as `atto.std/` and
`ores.std/`. Its bare root `std` is reserved too, avoiding filesystem
fallback in callers that consult the general namespace-reservation helper.
JUnit tests cover canonical/legacy spellings, Windows-style separators,
dot-segment traversal and a project-local shadow file. Relative local
imports (`./std/...`) retain separate explicit-path semantics.
These are authored compiler-resolver checks, not stepful hosted CI proof.

## Trailing separator canonicalization fix

Java `String.split("/")` silently drops trailing empty segments, so
the shared Core/Std path parser previously accepted the noncanonical
`atto.core/numeric/` spelling as the valid `numeric` module. The
validator now calls `split("/", -1)` and rejects every empty path
segment. Three new JUnit regression groups cover trailing and doubled
separators on all canonical/historical roots, while explicitly preserving
correct `.atto`/`.ores` identity aliases. The language compiler and
full guest tests still require an allocated GitHub runner to certify.

## Real compiler namespace admission negative group

Five negative `LinkedProgramRunner.validate` cases now compile
actual `.atto` entrypoints importing malformed `atto.core/`,
`ores.core/`, `atto.std/`, `ores.std/` and `std/` spellings
with trailing directory separators. The imported module names match
their target modules, so rejection cannot be explained by a synthetic
mismatched alias. These complement the path-helper tests and guard
against compiler call sites that might accidentally bypass the strict
library registry. They remain authored guest compiler tests until CI
runner admission is restored.

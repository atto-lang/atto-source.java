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

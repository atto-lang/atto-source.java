# Staged conformance sources — NOT an admitted test suite

These three native fixtures were present on the branch but absent from
`contracts/v1/semantics/cases.json`. The comparator rejects any such
unregistered file in `fixtures/`: this is a deliberate source-identity and
coverage protection, not an incidental glob failure.

The source bytes and Git blob identities were preserved when moving the files
to this staging directory, outside the comparator's active `fixtures/` scope.
These files have **not** been tested or asserted as passing.

To promote each source, (1) establish tested and reviewed expected behavior
on GraalVM and LLVM, (2) add a unique `cases.json` entry with required vs
pending backend support and the correct expected diagnostic/exit kind,
(3) move its original bytes to `fixtures/`, (4) run the complete comparator
test and verify the pinned suite digest and real guest evidence.

Do not weaken the orphan-fixture equality check, silently ignore fixtures
in the active directory, or mark absent backend execution as required/passing.

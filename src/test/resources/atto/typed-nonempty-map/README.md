# Explicit typed nonempty Map guest slice

Draft implementation stacked on Java PR #2725. Admit only
`~map<string,int>("key" => 1)` and the canonical scalar key/value domains
already used by the empty Map path.

Only an exact-head full Maven/JUnit and Truffle guest execution with stepful
source/runner provenance can certify these fixtures. Parser and isolated
storage runs are supporting evidence, not guest execution.

Required: `mvn -Dtest=TildeRecordLiteralGuestTest,TypedEmptyMapGuestTest,TypedNonemptyMapGuestTest test`.
Still excluded: move-only values, alias/unconstrained generic witnesses,
cross-actor transport, Native Image and other backends.

# Unregistered source candidates

Files under `library/policy/` are **not** compiler-owned library units. The
closed registry in `CoreLibrarySources` never resolves them; they are kept as
reviewed candidates (alternative namespace-form spellings, edge-case guests)
until they are merged into a registered `library/<layer>/<unit>.atto` unit.

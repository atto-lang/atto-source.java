# Oreslang overload/indexing audit

This branch audits caller-visible arity overload identity and comma-separated indexing arity. Function, method, and constructor overload selection must not use return types or generic-parameter count as a dispatch dimension. Matrix-style `value[i, j]` is a single indexing operation and must remain distinct from `value[i][j]`.

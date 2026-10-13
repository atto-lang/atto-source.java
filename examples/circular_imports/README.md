# Circular imports — lazy execution

Oreslang intentionally permits import cycles. Run:

```bash
mvn -q -DskipTests exec:java -Dexec.args="examples/circular_imports/a.ores"
```

`a.ores` imports `b_value` from `b.ores`, and `b.ores` imports
`a_value` from `a.ores`.

The loader discovers and type-checks the complete graph, then links **all**
code units without running imported initializers. Before entry `main()`,
only the entry unit's `init()` executes. The imported unit's `init()`
executes on the **first actual imported call**, and only once.

Expected output:

```text
init-a|main:Ainit-b|B
```

A symbol's import or export alone is inert; a call is the execution boundary.
If A.init calls B and B.init calls back into the still-initializing A,
the runtime fails closed with a recursive initialization diagnostic rather
than allowing a partially initialized unit to execute.

The strongly connected component remains an all-linked-before-any-init
barrier. Its member ordering is deterministic for link diagnostics, not an
implicit eager initialization order.

# Experimental defer/finally conformance programs

These three files were formerly orphan files in `../fixtures`, causing the
strict `validate_suite()` inventory invariant to fail before *any* conformance
proof could run. They are deliberately **not** registered in `../cases.json`.

- `native-defer-field-return.ores`
- `native-defer-lifo-cfg.ores`
- `native-try-finally-return-snapshot.ores`

The semantic report protocol currently restricts `native_exit.expected_exit`
to 0–125. Some of these programs intentionally compute results beyond that
range, and no verified Java/LLVM expected-outcome ledger has been approved for
them. Recording guessed expected values or marking an unverified backend
`required` would create false conformance evidence.

The strict suite still checks `../cases.json` against every `*.ores`
directly under `../fixtures`. Neither this directory nor its contents are
included in the suite digest, and this directory does **not** confer a test
pass or parity certification.

To promote a program:
1. Add an explicit supported observation mechanism (possibly a JSON integer
   result instead of process exit status) and a validated oracle.
2. Record backend readiness truthfully; never claim a pending backend passed.
3. Move the program into `../fixtures` and add a case to `../cases.json`
   in the same change, with tests confirming manifest parity.
4. Require real Java/Graal and LLVM execution results.

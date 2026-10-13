# Unadmitted native runtime fixtures

These native defer/finally programs were previously placed directly in
`contracts/v1/semantics/fixtures/`, despite having no case entries in the
versioned `cases.json`. That broke the strict manifest-to-fixture bijection.
They have been preserved **byte-for-byte** here, rather than deleted, added
to the manifest as fake passing cases, or silently excluded by the validator.

They are NOT semantic conformance cases yet. Their runtime results may
exceed the suite's documented 0–125 process-exit limit, and independent
Java/Graal plus LLVM execution has not been verified on pinned revisions.

Before promoting one to `fixtures/`, add a unique case to `cases.json`,
choose a valid result oracle that obeys the constrained exit contract,
prove both backends actually execute the exact source bytes, and re-run the
strict suite with its content-bound digest. Do not label this storage area
as passing parity evidence.

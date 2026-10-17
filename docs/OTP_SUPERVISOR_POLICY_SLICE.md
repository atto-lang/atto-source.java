# OTP supervisor policy (first executable source slice)

This is a deliberately constrained piece of `stdlib/otp/supervisor.ores` that
computes **restart selections and policy admission**, in Oreslang source.

- `restart_span(strategy, failed_index, child_count)` checks index bounds and
  returns a half-open child range for `one_for_one`, `one_for_all` and
  `rest_for_one`.
- `should_restart(policy, normal_or_shutdown)` validates
  `permanent`/`transient`/`temporary` classifications.
- `restart_budget_allows(restarts_in_window, max_restarts)` preserves the
  one-child compatibility check.
- `restart_budget_allows(restarts_in_window, requested_restarts, max_restarts)`
  charges an entire replacement span atomically. A `one_for_all` or
  `rest_for_one` episode cannot bypass restart intensity by counting a
  multi-child replacement as one restart. The comparison is subtraction-based
  to remain overflow-safe.
- Neither helper tracks real timestamps. Trusted supervisor CONTROL must own the
  monotonic restart window, deterministic backoff, physical quiescence and
  escalation.

This is **NOT** a functional OTP supervisor behaviour. The source module
cannot grant CONTROL, monitor a child, mint ActorRefs, handle panics, own a
restart clock or perform restarts. Actor restart lifecycle and GenServer
remain incomplete; the other five `stdlib/otp/*.ores` files are intentionally
not registered or made to look available yet.

The one-actor-no-OS aspiration is separate from Java/Graal's hosted
execution: these JUnit guest tests prove at most execution with the Java
backend. They do not prove LLVM, Cortex-M, allocation freedom, an embedded
runtime, or conformance of the complete OTP behaviours.

## Run the guest checks

```sh
mvn -q -Dtest=NativeOtpSupervisorPolicyTest,NativeCoreLibraryTest test
```

Keep PR draft until this command executes on the exact commit and is green.
Do not mirror this private compiler tree into an unapproved CI organization.

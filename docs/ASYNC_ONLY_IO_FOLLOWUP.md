# Async-only I/O follow-up

Canonical guest core/stdlib I/O names are asynchronous by default. Core and standard library do not expose synchronous/blocking I/O fallbacks. Any target without a verified readiness/completion provider must fail closed before host I/O. Compiler/build tooling is outside this guest-runtime invariant.

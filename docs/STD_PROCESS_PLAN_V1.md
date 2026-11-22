# std.process — Java backend implementation plan

The first source-first slice is `stdlib/process_policy.atto`, published as
`ores.std/process_policy`. Its functions are pure guest-side admission checks,
**not an OS process launcher**. The host must validate and reserve everything
again at the authoritative spawn boundary.

## Next guest-visible API

```atto
let ChildProcess child = await process.spawn(
  Command.new("/usr/bin/tool").args(["--version"]).stdout_pipe()
);
let ExitStatus status = await child.wait();
```

`spawn` must return `Result<ChildProcess, ProcessError>`; its handle is an
owned, generation-bound capability and must never expose a raw PID as authority.
`stdin`, `stdout`, and `stderr` are bounded asynchronous byte channels;
`run` pumps stdout/stderr concurrently with wait, enforces timeout and
capture limits, and reports spawn errors separately from exit status.

The Java provider must use an explicitly permitted `ProcessBuilder` launch
off actor-carrier threads, and `Process.onExit()` or equivalent wakeups.
Its RUN authorization must use `OresContext.requirePermission`, with per-actor
capability attenuation and command allow/deny rules. Explicitly check cwd,
environment, symlinks, inherited stdio, executable identity, and all resource
limits. Deny shell interpolation by default; expose a separate shell API only
behind a separate grant.

On cancellation/scope exit, close stdin, request graceful termination, enforce
a bounded grace period, force kill if still live, reap once, release pipe
resources and child budget. Detach must be separately authorized. Never
promise that killing one PID kills descendants: process group/job ownership
requires explicit setup.

Raw POSIX `fork()` must not duplicate a running multithreaded actor VM.
Prefer `posix_spawn()` on Unix and `CreateProcess` on Windows. For native
fork+exec fallback, run only an async-signal-safe child path followed by exec.

## Evidence / status

`tests/fixtures/std-process-policy-v1/main.atto` is a positive/negative
boundary regression with expected exit 42. The source is checked in but
guest execution, JVM host process spawn, AOT, stream ownership, lifecycle
and cancellation are **NOT VERIFIED** in this draft.

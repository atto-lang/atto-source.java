# std/process — Java backend implementation (draft)


## Restricted root-context guest bridge — source committed, NOT EXECUTED

In addition to the previously documented host-only `TrustedChildProcessRunner`,
the branch now contains a separate `NativeChildProcesses` JVM provider,
registered on `OresContext`, with evaluator dispatch and
`TypeChecker` signatures. The built-in **root-context** guest invocation is:

```atto
let started = await process.spawn("/absolute/executable", ["arg1"], "/absolute/cwd");
if started.is_err() then
  return 1;
fi
let child = started.unwrap();
let output = (await child.collect_output())?;
let status = (await child.wait())?;
```

**Asynchronous is default:** `spawn`, `collect_output`, `wait`,
`terminate` and `kill` have no `_async` suffix; any intentionally blocking
guest API would use `_blocking`. `try_wait` is a nonblocking poll.

This root-context bridge uses real `ProcessBuilder` and `Process.onExit`,
bounds concurrent child reservations to 16, captures up to 1 MiB per stdout
and stderr stream with concurrent continuous drainage, closes stdin, clears
the environment and requires absolute argv/executable/cwd. It checks
`OresContext.requirePermission(RUN)` and `READ` on requested and canonical
paths. It rejects actor-originating launches until actor-owned physical
retirement and supervision are implemented. It does not grant a shell, raw PID,
process-tree kill, arbitrary env, stdin streaming, detach, or root-free access.
A nonzero exit status is `Ok(ExitStatus)` rather than `Err`.

This is **not yet the public `.atto` `Process.spawn(Command)` API**. The
`std/process` `.atto` module currently owns a source-level Command/ExitStatus
model; connecting it to this guest builtin and enforcing all stdio-mode
options remains required. The `NativeChildProcessesTest` and
`AttoChildProcessGuestTest` suites and
`tests/fixtures/std-process-runtime-v1/guest.atto` are checked in but
**NOT EXECUTED**. The new Java provider is independent from the earlier
host-only runner whose historical smoke result is described below.
Executable replacement after path checks, process descendant control,
graceful escalation, cancellation after abandoned await, and Native Image
remain unproven. Keep PR in draft until compiler, guest and security gates pass.


## Public import naming

The normal import is `import module Process from "std/process";`.
The explicit disambiguating import is `import module Process from
"atto.std/process";`. The two forms share one compiler-owned source and
one linked code-unit identity. `atto.std/*` is reserved before project-file
resolution, so a local package cannot impersonate it. The unqualified
`std/*` spelling is supported as the normal hosted path.

`ores.std/process` and `ores.std/process_policy` are **not accepted**.
Older `ores.std/*` imports for previously shipped modules remain temporary
compatibility aliases. Do not expand the old namespace for new modules.

## Implemented in this draft

- `stdlib/process_policy.atto` (`std/process_policy` or `atto.std/process_policy`): pure admission and size-policy helpers.
- `stdlib/process.atto` (`std/process` or `atto.std/process`): `Process.Command`, `Process.ExitStatus`, explicit stdio modes, `command`, `command_with_args`, `with_stdio`, `valid_stdio`, and `succeeded`. These are guest-side **value contracts, not a launcher**. The host must snapshot and revalidate any mutable arrays.
- `src/main/java/dev/oreslang/runtime/TrustedChildProcessRunner.java`: **host-only**, package-private, direct-argv `ProcessBuilder` execution; bounded 1..32 child permits, bounded reader/wait executors, concurrent stdout/stderr capture (4 MiB maximum per stream), timeout/force-kill/reap, closed stdin, explicitly supplied environment, and a mandatory host admission callback.
- `tests/host/run-std-process-smoke.sh`: independent JDK executable smoke for real child launch/capture, host gate denial, bad argv, byte capture limit, and timeout termination; invokes `javac` and `java` without Maven. Run with `bash tests/host/run-std-process-smoke.sh`.
- `tests/fixtures/std-process-policy-v1/main.atto` and `tests/fixtures/std-process-v1/main.atto`: **source-only** guest fixtures with expected exit 42 (NOT EXECUTED on compiler).

The standalone JDK script now also compiles `CoreLibrarySources.java` and
tests `std/*` vs `atto.std/*` source identity, path hardening, and legacy
process import rejection. These tests are **committed but unexecuted** at the
current exact PR head.

## Future public guest API — NOT IMPLEMENTED

```atto
import module Process from "std/process";
// Planned API; the following call does NOT compile or execute yet:
let Result<Process.ChildProcess, Process.ProcessError> started =
    await Process.spawn(Process.command("/usr/bin/tool"));
```

Required surface: `spawn(Command) -> Future<Result<ChildProcess, ProcessError>>`,
`run(Command, RunOptions) -> Future<Result<Output, ProcessError>>`,
`child.wait() -> Future<Result<ExitStatus, ProcessError>>`,
`child.terminate()`, `child.kill()`, `child.stdin()`, `child.stdout()`,
`child.stderr()`, `child.close()`. Pipe endpoints must be ownership-checked,
bounded asynchronous byte streams. `wait` and `run` must simultaneously
drain both output streams. No raw PID may be treated as a kill or wait authority.
No detach by default.

**Critical security gap:** the host runner's `Admission` callback is only a
pluggable trusted-host hook, **not** an actual `OresContext.requirePermission`
bridge. An authoritative Atto guest provider still needs actor/isolate identity
and incarnation binding, effective RUN permission and per-actor capability
attenuation, command allow/deny rules, environment/cwd grants, executable
identity, deterministic reservation and release of resource quotas, and
revocation checks at the actual spawn boundary. Java pathname resolution and
`ProcessBuilder.start()` are not descriptor-pinned; executable replacement
(TOCTOU) remains a risk. Do not expose this host runner to untrusted tenants.

The current Java runner returns a bounded **captured** outcome, not streaming
pipes, and closes the child's stdin. It does not guarantee killing descendants,
provide a shell, or claim cancellation and cleanup parity with actor task
scopes. A shell API, inherited stdio, process-group control, detach, and raw
OS environment must each require explicit separate authority. Full actor
cancellation needs a generation-bound owned handle and verified physical
resource retirement. Runner.close is a blocking host operation, never a guest
actor carrier operation. `Output` must preserve timeout and capture-exceeded
as distinct from ordinary nonzero exit status.

**POSIX fork:** raw `fork()` must never duplicate a running multithreaded actor
VM. Unix backends should prefer `posix_spawn()`; Windows should use
`CreateProcess`. If a native fork/exec fallback is necessary, only async-signal
safe work may occur between fork and exec.

## Testing and evidence

A previous local JDK 21 standalone run of the host prototype was reported to produce
`STD_PROCESS_HOST_SMOKE_PASS` for capture, admission rejection, argv validation,
capture limit and timeout. The newly changed files on this PR head have **not** been re-executed on a runner. The prior checkpoint is **not** a GitHub Actions run, full-project
compile, or native-image test. The real compiler `.atto` guest fixture and
GraalVM AOT have **NOT EXECUTED** on this head; do not mark PR Ready or claim
`std/process` parity. Source-org zero-step Actions must be treated as
`NOT_EXECUTED`. Private cross-org CI mirroring requires independent approval
of source tree and destination, and full blob/tree verification.

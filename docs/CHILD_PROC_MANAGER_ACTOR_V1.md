# ChildProcManager actor contract (proposal; not executable yet)

**Status:** design and fail-closed parser/permission regression contract. No built-in
`ChildProcManager` type, child-process lowering, PTY backend, or shell runtime
is shipped by this document. This does not authorize spawning processes.

## Source model

```ores
define actor CPM extends ChildProcManager as
  async on_start(): void {
    const child = await self.spawn_process_async(
      executable: "/usr/bin/python3",
      args: ["-u", "worker.py"],
      cwd: "/srv/app",
      stdin: #pipe,
      stdout: #pipe,
      stderr: #pipe
    );
    await child.stdin.write_async("hello\n");
    // Proposed stream composition: bounded, owned, cancellable.
    for await const chunk of child.stdout {
      stdio.println(chunk);
    }
    const status = await child.wait_async();
    stdio.println(status.code);
  }
end
```

The example specifies intended behavior; `for await`, typed process handles,
named-argument syntax and the built-in superclass are not all implemented.

A `ChildProcManager` is an actor with runtime-managed subprocess capabilities.
It is **not** a Java superclass, a native-process-per-actor guarantee, or a new
isolate. Child processes are real OS processes, not Oreslang actors.
The manager retains its own actor heap/arena and mailbox; process pipes and
handles live in a bounded VM-owned I/O/resource table, never as raw transferable
Java `Process`, `ProcessBuilder`, file descriptors, or `ProcessHandle` values.

Existing `define actor X extends Y as ... end` syntax already parses in Java.
The compiler must install an intrinsic runtime actor base type, validate
inheritance, and lower its reserved methods before the declaration can typecheck
and run. No fallback to a user-defined class named `ChildProcManager` or
reflection-based Java construction is permitted.

## Actor-kind inheritance and shorthand

Future syntax may normalize to exactly one intrinsic actor base plus zero or
more contracts/traits (no multiple inheritance of resource owners):

| User spelling | Desugared kind (future) |
| --- | --- |
| `define actor X as` | `Actor` |
| `define serial actor X as` | `SerialActor` |
| `define http actor X as` | `HttpActor` |
| `define serial http actor X as` | `SerialHttpActor` |
| `define actor X extends ChildProcManager as` | `ChildProcManager` |

These aliases are **not** currently promised by the Java parser. Preserve
existing private/shared/untrusted kind restrictions; extending a base cannot
change actor isolation domain or execution mode. A transport-specific actor
base can be composed using an explicitly validated capability/contract
composition mechanism, not contradictory actor keywords or multiple host
resource superclasses. `Foo<messages=M>` is separate named compile-time
metadata syntax; do not silently reinterpret a generic type argument as a
runtime quota.

## V1 child-process API (proposed)

- `self.spawn_process_async(spec: ProcessSpec) -> Future<ChildProc>`: executable
  and **argv array**, never implicit `sh -c`; process created asynchronously on a
  VM-owned I/O executor, not on the actor carrier.
- `ChildProc.stdin: WriteChannel<ByteBuffer>`; `stdout` and `stderr` are
  independent bounded `ReadChannel<ByteBuffer>` streams with explicit EOF.
  Text encoding/line splitting are opt-in adapters.
- `ChildProc.wait_async() -> Future<ExitStatus>`: wait/reap once and allow
  repeat observers; `ExitStatus` distinguishes exit code, signal, cancelled,
  launch failure, and supervisor termination.
- `ChildProc.signal(sig)`, `terminate_async(grace)`,
  `kill_async()`, `close_async()`: only for processes owned by this manager.
  Signals are portable enums with explicit `Unsupported` on unavailable OSes.
- `self.open_shell_async(kind: Shell, opts: ShellSpec) -> Future<ShellSession>`:
  separate **explicit shell authority**. Supported candidates are `#sh`,
  `#bash`, `#zsh`; shell executable is resolved to a canonical absolute
  path and separately authorized. Windows uses an explicit compatible backend
  or rejects `Unsupported`, never a silent `cmd` substitution.
- `ShellSession` includes stdio streams, exit status, optional PTY, terminal
  size and resize, signal forwarding, and stdin close. Interactive TTY is
  distinct from three independent pipes; PTY support is optional and gated.
- File stdio redirection, FD/handle duplication, inherited stdio, socket
  bridging, and process pipelines use **typed endpoints**. Redirecting any
  endpoint requires the corresponding file/network/stdio capability and no
  cross-actor ownership escape. `#inherit` is not the default.
- All guest-visible stream writes/reads and spawn/wait/close are suspending,
  with `_async` suffix where exposed as I/O APIs. No synchronous JVM blocking
  on a scheduler/carrier; runtime internal blocking workers are bounded.

## Admission and security invariants

1. Deny by default. The existing `IsolatePolicy.Capability.CHILD_PROCESS`
   and scoped `RuntimePermissions.Permission.RUN` must **both** authorize
   every launch. The actor's effective policy (not the ambient parent context)
   is checked at admission, then its immutable, attenuated decision is carried
   to the off-carrier spawn worker. There must be no actor-local-policy loss
   from `ThreadLocal` context switching.
2. Resolve executables to canonical absolute paths, reject missing/ambiguous
   executables, and check denied aliases/symlinks. Avoid PATH resolution unless
   a separately audited deterministic search policy is provided. Pinning is
   best effort absent OS descriptor-based exec; never claim it prevents
   concurrent filesystem replacement.
3. `cwd` must be explicit and permitted. Clear inherited environment by
   default; allowlist each passed environment key through `ENV`. Preserve a
   restricted execution profile for secrets; no implicit Java env copy.
4. `--allow-run=/path/to/tool` is **not an OS sandbox**: even a seemingly
   harmless executable can access files, network, or spawn grandchildren.
   A shell especially delegates arbitrary code. Untrusted actor execution of
   native processes stays disabled until there is OS-enforced confinement
   (namespaces/seccomp/cgroups, sandbox-exec/Seatbelt, AppContainer/Job Objects,
   or a proven equivalent). Shell sessions require separate, explicit,
   administrator-approved authority, not merely an inheritance clause.
5. The manager tracks a maximum live child count, aggregate bytes in pipe
   queues, active readers/writers, outstanding waits, pending spawns, and PTYs.
   Quota exhaustion rejects admission before resource allocation.
6. Child handles are affine actor-owned capabilities. `rt borrow` may inspect
   a handle within its owner, `rt take` transfers ownership only across
   explicitly supported ownership boundaries, and `rt share` cannot turn a
   live child/pipe into a sendable read-only object. Actor messages carry
   immutable child-event snapshots or validated, opaque delegation tokens,
   never raw OS handles.
7. Never merge stderr into stdout without explicit opt-in. EOF, truncation,
   slow consumers, partial writes, cancellation, and buffer exhaustion must
   behave deterministically. Blocking OS pipes require bounded pumps.
8. On actor cancellation, `on_start` failure, `self.end`, supervision
   restart, hot reload, and runtime shutdown: fence new spawns, close stdin,
   signal/terminate, escalate after a bounded grace period, reap direct children,
   and close all streams exactly once. Descendant cleanup requires OS process
   group/job tracking; `ProcessHandle.descendants()` is insufficient as a
   security/termination guarantee.
9. A shell's `exit` status must **not** call Oreslang `process.exit()` and
   must never terminate the OresVM or its supervisor. Never expose host Java
   `ProcessBuilder` through guest interop.

## Compiler/runtime implementation gates

1. **Grammar:** preserve `define actor ... extends ...`; add explicit
   metadata describing *built-in actor base* vs user actor parent, and reject
   incompatible kinds, more than one resource-owning base, and attempted
   overrides of reserved process methods. Keep shorthand syntactic sugar only.
2. **Typechecker:** register `ChildProcManager`, `ChildProc`,
   `ProcessSpec`, `ExitStatus`, and stream/PTY types with checked effects,
   ownership rules and correct generic handling. Reject calls from unrelated
   actors and compile-time checks that omit an effective RUN grant.
3. **Java runtime:** implement bounded actor-owned registry, off-carrier
   spawn/pipe pump/wait, isolation policy propagation, cleanup fences, metrics
   and structured cancellation. Never export raw Java process objects.
4. **LLVM runtime:** same source semantics via OS-specific process adapters;
   native handles stay in actor-owned registries with no tracing GC and
   deterministic cleanup. Unsupported platforms return explicit errors.
5. **Conformance:** positive argv/Unicode/binary I/O and exit-code tests;
   negative permissions, PATH/symlink, env leak, cross-actor handle transfer,
   pending-spawn vs stop, double-wait, no-consumer backpressure, signal racing
   exit, reparented grandchildren, shell-denial, resource exhaustion,
   and Windows/Unix shell availability cases.
6. **Validation:** Java parser + JUnit, Java actor runtime and security suites,
   LLVM source parity and native unit tests, and an end-to-end shell/PTY test
   behind a tightly scoped opt-in CI permission profile. No CI success may be
   claimed merely because a workflow was triggered with zero executed steps.

The initial parser/permission regression tests accompanying this design
exercise existing safe behavior. They do **not** demonstrate an implemented
child-process actor runtime.

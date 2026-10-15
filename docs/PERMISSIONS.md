# Oreslang executable permissions (Java/GraalVM)

Oreslang's standalone Java/GraalVM launcher supports Deno-style scoped host-I/O
permissions. These flags configure **guest** authority; they do not restrict
what the trusted compiler, module loader, or host embedding application can do.

## Examples

```sh
# Guest I/O is denied unless explicitly granted.
oreslang-compiler app.ores

# Allow reading only one tree and connecting to one HTTPS host/port.
oreslang-compiler --allow-read=./assets --allow-net=api.example.com:443 app.ores

# Restrict an otherwise broad grant.
oreslang-compiler --allow-net --deny-net=metadata.internal app.ores

# Both spellings grant all guest I/O categories; review before using.
oreslang-compiler --allow-all app.ores
oreslang-compiler -A app.ores
```

Supported categories are `read`, `write`, `net`, `env`, `run`, `sys`,
`ffi`, and `import`, with `--allow-<category>[=<scope,...>]` and
`--deny-<category>[=<scope,...>]`. `-R[=PATH,...]` and `-W[=PATH,...]`
are aliases for read and write. Denials override grants. Malformed or
empty list elements are rejected rather than silently ignored. Commas
within a path are not yet supported by this parser: use individual
non-comma paths or wait for explicit escaped-comma support.

`--no-prompt` is accepted, but Oreslang never interactively expands
permissions. `--permission-check=compile` can reject statically
recognizable *coarse capability* violations. It is **not** a proof that
every dynamic path, network address, or imported module will be accepted.
`--permission-check=runtime` defers these errors to runtime; runtime
checks remain required either way.

Network permission scopes use a DNS name, optional leading `*.` wildcard
for subdomains, numeric IPv4, or **bracketed** numeric IPv6 address;
optional ports are `0..65535`. DNS names and equivalent numeric
address spellings are normalized before matching. Port 0 is used for
OS-assigned listener ports. A bare host permits any port on that host.
A wildcard does not match its parent domain.

The legacy `--allow=CAPABILITY,...` syntax explicitly grants coarse
capabilities. A scoped Deno-style rule for a category takes precedence
over the legacy capability grant for that same category. A default
developer embedding policy does **not** implicitly grant CLI guest I/O.

## Security limits and native backends

- Scope checks and coarse isolate capabilities are separate layers. A
  guest operation needs both. Do not expose arbitrary host reflection
  or native libraries as an alternative filesystem/network API.
- `--allow-run` and `--allow-ffi` are potentially sandbox-breaking:
  subprocesses and native libraries may operate with the host's OS
  privileges. Do not grant them to untrusted programs without additional
  isolation.
- Canonical-path checks mitigate symlink traversal, but an attacker
  concurrently mutating directories can still create time-of-check/
  time-of-use races without an OS-enforced sandbox or descriptor-relative
  filesystem access. Do not claim hostile multi-process filesystem
  isolation from path string checks alone.
- DNS authorization by name is not equivalent to binding an authorized
  IP address. DNS rebinding, aliases, redirects outside Ores-owned
  clients, and proxies require deployment-specific controls.
- A GraalVM native-image executable **containing the OresMain launcher**
  uses the same runtime permission plumbing. A separately compiled
  LLVM guest executable is **not** thereby granted a Deno-style sandbox:
  the current LLVM compiler rejects unknown permission CLI flags, and
  the native backend must not claim support until its privileged I/O
  operations are mediated by an equivalent policy.
- Static source loading by the trusted compiler is outside guest-I/O
  permission scope. Protect the compiler process separately when
  compiling untrusted code.

For hostile code, use operating-system sandboxing or an isolated
container/VM in addition to runtime permissions. The permission model is
defense in depth, not a substitute for OS-enforced privilege separation.

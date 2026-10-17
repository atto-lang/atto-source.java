# Oreslang scoped permissions: compile-time diagnostics and runtime enforcement

Status: Java candidate on draft PR #1034. **Not production-certified**; exact-head JDK 25/GraalVM/JUnit and Native Image validation is outstanding. This feature does not yet bake a signed permissions manifest into an AOT executable.

## Standalone CLI flags

```sh
# Default deny for privileged host I/O; no interactive prompts:
oreslang-compiler main.ores

# Scope network and environment access at run time:
oreslang-compiler --permission-check=runtime \
  --allow-net=api.example.com:443 --allow-env=PORT main.ores

# Reject definite unauthorized literal-resource calls before execution:
oreslang-compiler --check --permission-check=compile \
  --allow-env=PORT --allow-read=/work/data main.ores

# Denies always override allows regardless of argument order:
oreslang-compiler --permission-check=compile \
  --allow-net=api.example.com:443 \
  --deny-net=api.example.com:443 main.ores
```

Supported grant/deny pairs are `read`, `write`, `net`, `env`, `run`, `sys`, `ffi`, and `import`, plus broad `--allow-all` (unsafe for untrusted guests). Bare `--allow-net` grants a category; `--allow-net=host:port` scopes it. `--allow=CAPABILITY` is a coarse isolate ceiling, **not** a scoped host-I/O grant. `--no-prompt` is always in effect. Ordinary standalone execution must never inherit embedding/developer capabilities when no `--allow-*` flags are supplied.

## Semantics

| Check mode | Compile/check phase | Runtime |
| --- | --- | --- |
| `compile` | Reject missing coarse effects and definite denials of literal resources for recognized built-in functions; dynamic/unknown resource values are not statically proved | **Still** enforce host-owned scoped policy on every actual I/O |
| `runtime` (default) | Defer relevant permission diagnostics so dynamic values may compile | Enforce host-owned scoped policy on every actual I/O |

Current literal preflight supports `env.get` / `env.has`, selected `fs.*` read/write/mutation functions (including `fs.mkdir_all` and `fs.remove`), `network.connect(host, port)` when host and port are source literals, and literal `http.get_text`/`http.post_text` URLs (effective origin port; the current Java transport additionally requires canonical numeric IPv4 and disallows unresolved hostnames). This is a deliberately incomplete static analyzer. It must never be treated as proof that guest code is safe: imported wrappers, runtime-computed strings, reflection, host interop, path symlinks, and DNS rebinding require enforcement at the actual host I/O boundary.

The Java linker runs compile-mode checks against **all collected Oreslang modules** before guest initialization. `--check` and `--build-analysis` also receive explicit scoped policies. A denied constant resource is an error in compile mode, while an unknown resource remains deferred; the latter never authorizes runtime use.

### Build-time *ceiling* versus run-time *grant*

A future sealed Native Image/LLVM executable requires an independently reviewed, immutable **build-time permission ceiling** and an explicit scoped **runtime grant**. Effective authority must be their intersection, further reduced by actor/isolate capabilities and `--deny-*`; runtime flags must never expand the build ceiling. The current `--permission-check=compile` selects static diagnostics only; it is **not** an AOT policy-sealing or OS sandbox feature.

## Migration and safety gates

- `github.com/flags-2-env` remains the required CLI parser integration (draft Java PR #938). Do not introduce a competing ad hoc flag-parser implementation.
- Public CLI uses `RuntimePermissions.denyAll()`; legacy `RuntimePermissions.fromCapabilities()` is restricted to explicitly trusted embedder compatibility.
- Static checks and any policy serialization cannot replace Java socket's pinned-selected-IP checks, filesystem handle-based protection, subprocess admission, or host interop restrictions.
- The Java guest `fs.read_text` path now enforces a 16 MiB payload bound with strict UTF-8 decoding and a no-follow final open. Parent-directory renames/replacements are still a separate descriptor-relative hardening task; a path-based permission check is not an OS sandbox.
- Before claiming cross-backend parity, reconcile the shared test corpus: Java, LLVM, Forge and WASM currently contain 52 cases but .NET retains only 44 (missing eight padded ENV/RUN/SYS token cases).
- Incomplete LLVM/.NET/Forge source subsets and WASM zero-import runtimes must reject unsupported guest permission operations instead of claiming full parity.
- Production completion requires reviewed **exact-head** tests and the current 52 shared cases genuinely executed per backend (260 total after the .NET corpus is updated), with no skips or synthetic reports.

# AttoLang std/env v1 — Java / GraalVM

The public module is written in native AttoLang at `stdlib/env.atto`,
registered by `CoreLibrarySources`, and imported as:

```atto
import module std_env from "std/env";
```

## Public API

- `get(name): Option<String>` — missing = None; present empty = Some("").
- `has(name): bool` — an empty present value is true.
- `get_or(name, fallback): String` — use fallback **only if missing**, not denied/empty.
- `get_required(name): Result<String,String>` — missing is Err.
- `get_nonempty(name): Option<String>` — opt-in empty-as-absent.
- `get_required_nonempty(name): Result<String,String>` — empty/missing is Err.
- `parse_bool(raw): Result<bool,String>` — accepts exactly true/false/1/0.
- `get_bool(name): Result<bool,String>` — required strict boolean.
- `get_bool_or(name, fallback): Result<bool,String>` — fallback only if missing.

No mutation, enumeration, ambient Java interop or logging of secret values is
exposed by the library.

## Security boundary

`NativeIo.envGet` and `envHas` check portable key syntax and both coarse
`ENVIRONMENT` and per-key `RuntimePermissions.Permission.ENV` **before**
consulting any source. Explicit denies override broad allows. Invalid key names,
permission denial, malformed UTF-16, oversized UTF-8 and embedded NUL are
errors, never recast as None or fallback.

**Important: Truffle's `EnvironmentAccess.NONE` remains mandatory.**
`LinkedProgramRunner` now filters the host-supplied environment by permission
before configuring `Context.Builder.environment(filteredMap)`; the guest
intrinsics query `OresContext.env().getEnvironment()` and **never**
`System.getenv()` directly.

This preserves the embedding host's environment boundary:
- If the host provides `ATTO_ENV_FLAG=true` and authorizes its key, the guest
  can read that key.
- If host `PATH` exists but the embedder passes an empty map, even a broadly
  granted guest cannot read the ambient JVM value.
- If a key is denied, the snapshot builder does not call `map.get(key)`.
- If the coarse ENV capability is absent, the snapshot builder does not even
  enumerate source environment keys.
- An immutable snapshot contains at most 512 authorized keys, 16 KiB UTF-8 per
  value and 1 MiB aggregate UTF-8 values; excess is rejected before guest
  context creation, without printing the value.

`OresMain` intentionally supplies the process environment as a **trusted host
input** to the runner, but the same scoped permission filtering is applied.
Other embedding callers can pass a separate map to get deterministic,
isolated semantics. A Truffle context built without injected environment values
cannot acquire ambient JVM variables through `std/env`.

## Material test gates

`NativeAttoEnvStdlibTest` executes real guest fixtures
`src/test/resources/stdlib-parity/env.atto` (absent/fallback/strict bool) and
`env-injected.atto` (present, empty, strict boolean and host-PATH isolation),
and `env-context-isolation.atto` (independent context snapshots).
Additional host-policy tests check denied value getters are never invoked,
coarse permission denial avoids enumeration, key/value bounds, spoofing and
canonical package resolution.

Run on GraalVM JDK 25:

```sh
mvn -B -ntp -DfailIfNoTests=true -Dtest=NativeAttoEnvStdlibTest clean test
```

The dedicated `.github/workflows/std-env-guest-java.yml` requires an exact
source-head checkout and nonempty, unskipped Surefire evidence for every named
test. A workflow run with no executed steps is `NOT_EXECUTED`, not a failure
or pass of the compiler. External mirrors are forbidden unless the private
V3 source-export policy is independently satisfied; a source-free alternate
runner canary is only capacity evidence.

## Independent source-free GraalVM behavior receipts

With no Atto compiler source exported, an independently written minimal Truffle
guest was compiled and run on a private alternate GitHub Actions runner using
GraalVM JDK 25 and Truffle artifacts 25.3.4.1:

- [Source-free run 45051743255](https://github.com/channelsiege/atto-ci/actions/runs/45051743255) — executed actual Truffle `Env.getEnvironment()` with `EnvironmentAccess.NONE`; only explicitly configured keys appeared, and JVM-global `PATH` was absent.
- [Source-free run 45051945356](https://github.com/channelsiege/atto-ci/actions/runs/45051945356) — repeated those checks and verified two simultaneous Graal contexts with different values remained distinct, including repeat reads of the first context.

These prove Truffle API and external runner behavior; they **do not prove**
Atto guest module linking, parser/type-checking, or the contents of this private
repo. In particular, source-org Actions CI is not replaced by these synthetic
receipts. Security review [issue #1788](https://github.com/atto-lang/atto-source.java/issues/1788)
tracks the separate private source export DENIED hold.

**Verification: NOT_EXECUTED for this changed Java guest implementation.**
Do not mark the PR Ready or claim Native Image AOT parity until exact-head
guest execution succeeds, required security rules are clear and any AOT-specific
qualification also passes.

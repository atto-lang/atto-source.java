# AttoLang Live Runtime DevTools (MCP) — first Java slice

**Status:** prototype, read-only, opt-in. This document describes what the
feature branch implements, not completed multi-backend parity. The host-only
endpoint uses MCP `2026-07-28` stateless Streamable HTTP `POST /mcp`
with a deliberately narrow set of methods.

## Using it from a trusted Java host

```java
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.devtools.ActorRuntimeDevtools;
import dev.oreslang.runtime.devtools.LiveMcpServer;

// Generate a fresh, unpredictable >=32-character token in the trusted host.
// Never place the token in source, logs, URLs or guest-accessible memory.
try (ActorRuntime actors = new ActorRuntime();
     LiveMcpServer devtools =
         ActorRuntimeDevtools.start(actors, hostOnlyBearerToken, 0)) {
    System.err.println("MCP loopback port: " + devtools.port());
    // Run the actual actor host here; server shares the same ActorRuntime.
}
```

Disabled by default: neither OresMain nor guest code starts a listener.
Only `127.0.0.1` is bound. A per-process bearer token is required even for
discovery; Origin and Host restrictions mitigate DNS rebinding. Do not forward
the listener or deploy it behind a remote proxy. Remote OAuth/token audience
binding has **not** been implemented.

Implemented:
- `server/discover` for the 2026-07-28 protocol profile;
- `tools/list`, advertising exactly `atto.runtime_info`;
- `tools/call` for that read-only tool;
- actor count, configured maximum actor count and physical carrier backend
  from the attached **real ActorRuntime** instance;
- response bounds, JSON nesting bounds, duplicate-key denial, bearer checks,
  HTTP header checks, and sanitized errors.

Not implemented: listing actor identities, consistent typed actor snapshots,
heap dumps, thread/task introspection, external OAuth, remote transport,
subscriptions, hotload staging, actor-state migration, code generation
retirement, automatic prod enablement or AOT support. No arbitrary memory
reads, reflection or actor-private state escape. Do not advertise or assume
any of these capabilities.

## Test commands

Host-only, no external libraries (Java 21):

```sh
mkdir -p /tmp/atto-live-mcp-classes
javac --release 21 -d /tmp/atto-live-mcp-classes \
  src/main/java/dev/oreslang/runtime/devtools/LiveMcpServer.java \
  tools/live-mcp-smoke/Smoke.java
java -cp /tmp/atto-live-mcp-classes dev.oreslang.runtime.devtools.Smoke
```

Full runtime/JUnit integration, subject to Maven/Graal dependencies:

```sh
mvn -Dtest=LiveMcpServerTest test
```

The standalone smoke verifies only the host protocol and security gates.
The JUnit tests exercise a real ActorRuntime instance and actor-count change,
but **do not compile or execute guest .atto code**. Genuine guest tests and
other backend ports require separate execution evidence.

The feature is intentionally separate from `HotReloadManager` and
`ActorUpgradeEpochGate`: the latter currently cannot certify scheduler
quiescence or migration safety. No mutation tool may be added before those
prerequisites are proven.

Contract: `oreslang-language-specs.md/spec/atto-live-runtime-devtools-mcp-v1.md`.

# Actor causal chains in error diagnostics

Oreslang does not use the physical carrier thread stack as a cross-actor
stack: message sends, actor scheduling, and `await` sever that Java stack.
Instead, a logical trace is copied when a source-level actor invocation is
spawned, and an `ACTOR_MESSAGE` boundary records the receiving actor.

## Retention contract

For source-level `spawn actor_fn(...)` invocation chains sharing causal
context, an error retains the **16 most recent visited actors**, including
the **last five** for chains at least five actors deep. This is a separate
bounded history, so arbitrarily many await/select/call events cannot evict
the actor chain. Earlier actor hops have an explicit `elided_actor_visits`
count. The latest actor appears first in text and the JSON
`actor_history` array is oldest-to-newest.

The actor-history list is a *causal breadcrumb trail*, **not** a physical
stack of concurrently executing actors. Actors may no longer be running
when a descendant fails. Fan-out creates separate child trace snapshots;
it must not accidentally mix unrelated siblings' visited actors.

Each hop carries only the actor's function symbol and source site,
not message arguments, message bodies, private state, capabilities,
credentials, or physical Java thread identities. Actor logical source identifiers reject inputs greater than 4,096
UTF-16 units before expensive path/URL operations. Absolute filesystem paths
and source URLs are rendered as source basenames (or `<remote-source>` if a
URL has no path); relative source paths remain readable. Oversized identifiers
render `<source-id-too-long>`, never a credential-bearing prefix. Existing
control-character escaping applies consistently to text and JSON diagnostics.

## Machine-readable format

`--json` stderr diagnostics preserve schema `oreslang.error.v1`. Each
rendered logical trace can contain:

```json
{
  "actor_history_order": "oldest_to_newest",
  "actor_history": [
    {
      "actor": {
        "symbol": "Actor5",
        "site": {
          "source": "workers.ores",
          "line": 24,
          "column": 1,
          "generation": null
        }
      },
      "message_site": {
        "source": "workers.ores",
        "line": 24,
        "column": 1,
        "generation": null
      }
    }
  ],
  "elided_actor_visits": 0
}
```

The above is an excerpt of a trace, not a complete diagnostic envelope.
Older `events` can be elided independently.

At the Polyglot interop boundary, each `async_traces[]` element also exports
`actor_history` (oldest-to-newest), `actor_history_order`, and
`elided_actor_visits`. Its `frames` stream includes an `actor` entry for
all retained actor visits (newest-first), even if unrelated await events
have evicted the original actor-message boundary from the general event ring.
These are sanitized, immutable frame projections; no message payloads are exposed.

## Validation and scope

- Runtime tests exercise 10 actor hops with 1,500 await events and a
  200-hop chain that retains the final 16 actors.
- An executable eight-actor failure fixture is checked via CLI text and JSON.
- These tests are *requested*, not evidence of passing CI until run on the
  exact PR Git tree.

## Stacked Java ActorRef integration (draft #1684)

The follow-on Java [#1684](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1684)
adds an initial host-mailbox implementation, **not yet certified by executed CI**.

- `ActorRuntime.send` creates a child-owned pending actor-message boundary
  and stores it privately in the mailbox envelope. A hop is counted as an
  **actual actor visit only when the receiving callback enters its execution
  scope**. Rejected or never-run messages cannot inflate visited-actor history.
  An input payload cannot inject this runtime-only field.
- The receiver installs the trace only for the callback's scheduler turn;
  strict LIFO scope restoration prevents another mailbox message from
  inheriting it from a pooled carrier thread. Await resumes restore the
  suspended trace, while timer and next-tick events create independent forks.
- Runtime actor hops identify trusted ActorRefs by their runtime-issued UUID.
  The source location is explicitly **unknown**. Do not advertise accurate
  guest source spans for host ActorRef calls until compiler lowering supplies
  a validated span. Untrusted sender/recipient boundaries reset and redact
  the lineage; redaction intentionally limits cross-boundary visibility.
- Ordinary error attachment uses the same bounded logical diagnostic
  representation, preserving the original Throwable and error site.
  Scheduler-control suspension and fatal VM failures are never represented
  as guest errors.
- Host-runtime integration cases target actual mailbox message chains of 8
  actors, a 201-visit two-actor cycle with 185 elided visits, sibling fanout,
  host send isolation, pre-completed awaited Future continuation, and
  next-tick preservation. Source-level tests additionally require real scope
  entry before a queued boundary becomes a visited actor, and reject duplicate
  visits after resume.

This host runtime slice does **not** implement authenticated external
`ProxyActor` IPC/TCP/HTTP, supervisor CONTROL-chain transport, remote
redaction/serialization, or LLVM parity. No one should claim all transports
are conformant until actual compiled runtime tests and differential checks
pass. In particular, the existing Actions job failures with `runner_id=0`
and zero executed steps are **NOT_EXECUTED**, not proof of Java test success
or compiler failure. The branch remains draft and must not be mirrored to
unapproved cross-org carriers. See #1672 and the private-CI bootstrap issue
oreslang-platform-conformance#69.

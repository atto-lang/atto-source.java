# SHARED proxy publication retirement-quarantine witness — October 19, 2026

**Status: DRAFT; committed regression source is not an executed JUnit result.**

## Finding

`LogicalSourceActorRouterV1` already keeps a route-lock-owned
`retirementQuarantined` fence whenever published successor code must not
receive physical work before predecessor finalization and generation-pin debit.
Physical send and Task admission reject that state. However
`LogicalSourceActorProxyBridgeV1.Publication.authoritativeForDispatch()`
previously checked only bridge poison/close, mailbox close/freeze and route
epoch equality. A coherent diagnostic could therefore report **true**
while the physical router rejects all dispatch.

## Change

The router's immutable `SupervisorPublication` now captures
`retirementQuarantined` under the same `routeLock -> mailbox` nested
monitor order as code image, logical address and proxy mailbox epoch.
The bridge carries that exact value into its immutable `Publication` record.
`authoritativeForDispatch()` additionally requires no physical retirement
quarantine. This is observational: it does not mutate routing, thaw a
freeze, release code pins or treat a snapshot as a durable send permit.

## Regression and boundary

`LogicalSourceActorPublicationQuarantineTest` uses an actual source-compiled
SHARED actor, injects the route fence through test reflection, checks that
unchanged image/mailbox epochs no longer certify dispatch, then clears the
synthetic fence and executes an ordinary physical message. It verifies the
diagnostic protocol, **not** a full failing lease debit or authenticated
cross-process receipt.

An immutable publication becomes stale immediately after observation and is
not a substitute for the router's lock-protected physical admission checks.
PRIVATE/UNTRUSTED worker isolation, compiler-generated general message ABI,
remote authenticated mail.done and exact-head JDK25/Graal/JUnit/Native Image
execution remain required.

No private source was copied across organizations: reviewed mirror authorization
remains a separate security gate, regardless of alternate GitHub Actions minutes.

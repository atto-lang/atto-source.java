# ActorStream syntax gate (experimental)

Supported spelling targets:

```oreslang
return spawn Source() pipe spawn Transform() pipe spawn Sink();
return ActorUtils.pipe(spawn Source(), spawn Transform(), spawn Sink());
return ActorUtils::pipe(spawn Source(), spawn Transform(), spawn Sink());
```

`::` is the preferred explicit spelling for static functions when a
namespace/class resolution could be ambiguous. It maps to static namespace
member resolution rather than dynamic actor messaging.

All forms construct a bounded, compiler-validated `ActorStream` (2..16
distinct actor capabilities, shared exact closed nominal ActorMail contract).
The runtime binds the full route before any DATA ingress, with one outstanding
round trip: stage 0 -> ... -> stage N-1 -> ... -> stage 0. `ActorStream.send`
sends to the source and `ActorStream.get_done()` observes its physical
completion. Actors run on their own exclusive scheduler turns; stage grouping
does not permit concurrent native execution inside one actor.

The 15-actor fixture sends 14 forward and 14 backward typed hops, rejects
direct middle-stage ingress, duplicate registrations and early STOP through
the same scheduler authority checks as the previous three-stage route.
This gate is Java-first: native LLVM same-byte source acceptance and FD3
runtime attestation are separate release requirements.

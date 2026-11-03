# PR #385 semantic reconciliation — channel callable ownership

Historical head: `84ef9cd85b0aeb9f714e0121f22b37ffff5f83de`. Current-main baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

## Conflict resolution

- **Keep modern main's channel ownership policy.** A locally owned callable may be sent through a local channel with move/consumption rules; actor transport rejects callable authority transitively. This differs deliberately from this historical branch's unconditional *data-only Channel<T>* rejection. Reverting to the older rule would break admitted local-callback/channel usage.
- Keep main's bounded transport, borrow/escape checks, and nested borrowed payload denial. Preserve current implementations of the parser's pointerless `mut self` receiver and the async linked-import metadata; never replace them with historical compiler snapshots.
- Reintroduce only the historical `PointerlessMutableReceiverTest` fixture (exact blob `a4f9369abac2910ed5a933340fba5cf9aec9b5eb`) as supplemental coverage of mutable receiver ownership and nested managed references.
- Modern main's `ChannelCallableTransportTest` and `IncrementalFunctorStaticTest` take precedence over the historical divergent tests, because their revised ownership/async contracts are newer.

## Acceptance / release hold

This semantic integration preserves the historical commits in the first parent and current main as the second parent. It is not a CI certification or approval of all old semantics. Execute the new receiver test and channel/actor transport, borrow and incremental async suites on the exact merge head; independently review unexpected test failures before merging. Do not reinstate the original unconditional callable-channel ban.

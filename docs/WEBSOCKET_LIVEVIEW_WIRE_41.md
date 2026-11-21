# Java WebSocket ↔ Ores LiveView transport vector gate

This draft adds `WebSocketLiveViewWire41Test` against the current
unmerged Java RFC6455 host codec (PR #2061). It vendors the *identical*
41-vector JSON from Phoenix PR #14 and platform-conformance PR #278,
Git blob `931f58c1acd40e4c7e1b0b061f1711a0afe3c9b8`.

The new Java JUnit class has 41 inventory checks and **82 parameterized
decode observations** (whole stream and one-byte short reads). The
`ores-liveview-v1` application profile deliberately rejects binary text;
generic RFC6455 codecs may permit binary. Negative vectors require an actual
exception, but their **exact network close status** remains unverified until
the actor/transport layer maps reason -> close codes.

This does not claim a guest WebSocket, actor isolation or five-backend parity.
The exact-head JDK25/Graal/native-image suite is NOT_EXECUTED until an
authorized private runner produces nonzero steps. No code from this private
repo is exported to another GitHub organization.

Source SHA and all fixture bytes must be independently pinned at promotion.

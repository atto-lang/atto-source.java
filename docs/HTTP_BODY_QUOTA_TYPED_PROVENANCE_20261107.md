# Typed HTTP 413 provenance — local host characterization

**Status:** LOCAL_HOST_MODEL_EXECUTED; exact-head Java JUnit/GraalVM guest NOT_EXECUTED.

Java follow-up [PR #2659](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2659)
at source/test head `d53f9f296204c8a74f4a4de9dcc03b26bded5671` (Git tree `8f0c42c02fa7a9b8ed60d515bc271cc0cf24ecbd`) replaces
message-text based quota classification with
`NativeHttp.RequestBodyTooLargeException`. It is transport-owned and
thrown when an advertised request `Content-Length` exceeds the listener
quota, when `bodyText` reads over quota, or when streaming `readChunk`
crosses the quota. Only that *type* plus request-body-read context can
qualify for HTTP 413. Malformed inbound body IO errors remain HTTP 400;
server/response IO failures and arbitrary exceptions remain 500.

## Regression source committed to this PR

- `NativeHttpFramingAdmissionTest` checks exact `Content-Length` boundary
  acceptance (1024), first overflow (1025), decimal long overflow, forged
  `IllegalArgumentException("body too large")`, other runtime/error variants,
  null exception messages, and typed-marker response-side rejection.
- `NativeHttpRawWireAdmissionTest` now distinguishes a JDK-parsed 413 for
  declared over-quota length from 400 for overflowing numeric length; it
  continues to check the listener's post-rejection recovery and separate
  header-admitted versus successfully processed request-body counters.
- The host-test exchange explicitly shuts down the client output stream so
  the JDK's incomplete-body drain is not mistaken for listener deadlock.

## Independently executed JDK 21 smoke — limited scope

Ran `javac` and `java` from OpenJDK `21.0.12.1` on a **minimal
standalone** `com.sun.net.httpserver.HttpServer` reproduction of the
exact policy logic inspected in the PR, not the entire PR's Java class.

| Actual loopback raw wire | Expected | Observed |
| --- | --- | --- |
| Valid GET with Host | 200 | 200 |
| Content-Length: 1025 against 1024-byte quota | 413 | 413 |
| Content-Length: 9223372036854775808 | 400 | 400 |
| Valid GET after rejection | 200 | 200 |

**4/4 wire status + end-of-stream + recovery checks PASS**; only two
valid requests reached the handler's admitted-body path. No private
repository tree was mirrored or transferred to another organization.

## Non-negotiable merge gate

This host model does **not** establish successful execution of
`NativeHttpFramingAdmissionTest`, `NativeHttpRawWireAdmissionTest`,
complete Maven/JUnit tests, GraalVM Native Image, or compiler-backed
`.atto` guest. The source organization's Actions runner-admission
blocker is tracked under [#963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963);
the sampled synthetic retry still completed with `steps=[]` and its
log endpoint returned `BlobNotFound`. Keep this PR DRAFT until stepful
exact-head CI, independent valid approval and relevant security gates.

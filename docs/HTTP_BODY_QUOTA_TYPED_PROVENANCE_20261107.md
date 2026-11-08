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

## 2026-11-07 follow-up: strict HTTP Host authority admission

Code at exact pre-document head `ce9dc60278277912924930f07ce38b6a53472fa3` strengthens
`NativeHttp.validHostValue` beyond ASCII delimiter rejection: parse the
`Host` value as a URI authority using JDK in-memory parsing (no DNS/network
resolution), reject missing hosts, nonnumeric/empty/>65535 ports, unbracketed
IPv6, malformed bracket literals, and trailing authority garbage. Preserve
ordinary DNS hosts, ports 0..65535 and properly bracketed IPv6 literals.

**Source-target tests added:** two methods in
`NativeHttpFramingAdmissionTest` exercise 13 malformed authority strings
and 8 canonical valid authorities; four additional raw-wire checks in
`NativeHttpRawWireAdmissionTest` require status 400 for malformed Host
ports and IPv6 while preserving listener recovery and admission counters.

**Executed local characterization:** OpenJDK 21.0.12.1 `javac` compiled
an isolated host-admission class with the tested admission/typed-quota/URI
logic; its standalone main completed **38/38** explicit assertions.
Another local Java `HttpServer` bound to loopback using the same policy
returned **8/8** expected HTTP statuses and recovered afterward.
The isolated Java file and its test files had local Git SHA-1 blob hashes
`2cbe7baaa5ff6b3530f753d518b88c4840dfda83`,
`5d6f3a52ef89ff8a87e634192fd3939af430c67a`, and
`5bcfc3464ef458de3d6dbfa9e0dcd8f4d93b84ed`.
These hashes identify **local reproductions**, not exact GitHub whole-file
blobs; only the method under test was copied into the isolated class.
This is **HOST_SOURCE_FRAGMENT_LOCAL**, not the Java PR JUnit suite or an
AttoLang guest.

The source PR's direct exact-head `NativeHttp`, JUnit, and real compiler
workflows remain **NOT_EXECUTED** until GitHub Actions runner admission is
recovered. No CI gate or review was bypassed and no private code was
transferred to another organization.

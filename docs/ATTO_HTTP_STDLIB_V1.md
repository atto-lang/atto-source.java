# AttoLang HTTP stdlib — Java/GraalVM candidate v1

This PR introduces a compiler-owned `std/http` module written in `.atto`,
independently of LLVM/.NET/Wasm and independently of the privileged Java transport.

## Available now (source-level, pending exact-head guest CI)

```atto
import module http from "std/http";
val line = http.parse_request_line("GET /health HTTP/1.1");
val ok = line.is_ok();
val has_body = http.response_body_allowed(200, false, false);
```

Functions: `method_valid`, `method_is_safe`, `method_is_idempotent`,
`header_name_valid`, `header_value_valid`, `header_line_valid`,
`origin_target_valid`, `parse_request_line`, `status_valid`,
`status_is_success`, `response_body_allowed`,
`request_framing_allowed`, `body_bytes_allowed`,
`header_budget_allowed`. `RequestLine` is a typed value with method and target.

## Explicit non-goals and security boundary

* No HTTP client or server, socket, DNS, TLS, redirect, connection pool,
  decompression, streaming body or actor handler is supplied by this PR.
* Restrict header values to ASCII (HTAB, SP and visible characters) as an
  intentionally conservative subset. These checks do **not** accept the
  entire HTTP RFC grammar, nor normalize/canonicalize headers.
* The caller must enforce byte-limits *before* decoding, parse duplicate
  and conflicting fields, validate percent-encoding, canonicalize Host and
  Content-Length, reject obs-fold and ambiguous pipelining, and enforce
  permissions at each actual network operation.
* The status/body and framing helpers are admission hints, not evidence that
  any peer supplied a valid response, or that a request is authorized.
* No changes are made to the in-flight native-net implementation in
  [Java HTTP stdlib migration #495](https://github.com/atto-lang/atto-source.java/pull/495),
  pinned transport #1246 or selector transport #1251.

## Tests

`NativeAttoHttpStdlibTest` uses the real `LinkedProgramRunner` with
`src/test/resources/stdlib-parity/http-module.atto`, checks the compiler-owned
linked unit, and covers 18 positive/negative policy scenarios. Running or
merging requires **current-head guest execution**, not a host-only or zero-step
CI status.

Next: byte-buffer parsing, typed HeaderMap / Request / Response, streaming
bodies with backpressure and cancellation, actor HTTP server/client adapters,
and distinct native/managed implementations behind explicit capabilities.

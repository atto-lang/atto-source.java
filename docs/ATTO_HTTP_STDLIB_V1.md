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

## Extended Atto message API (candidate v2)

The implementation now also provides typed `HeaderField`, `RequestMeta` and
`ResponseMeta` values; checked `header_field`, `request_meta`, and
`response_meta` constructors; `parse_status_code`,
`parse_status_line`, `parse_content_length`, `content_length_valid`,
`route_pattern_valid`, `route_matches` and `route_matches_method`.
The public API remains `std/http`; `atto.std/http` may be supported as an
explicit disambiguation alias by future compiler import work.

### Semantics and security constraints

* HTTP parse helpers take **already bounded, pre-decoded** text; they do not
  parse raw TCP data. A transport must enforce wire byte limits before decode.
* Content-Length admits only ASCII decimal digits and a caller-supplied budget
  of at most 1 GiB, with subtraction-first overflow checks. Duplicate length
  fields, conflicting Transfer-Encoding, trailers, connection splitting and
  stream framing are rejected by the privileged transport (not inferred here).
* Route matching is intentionally conservative: literal and `:param` path
  segments, exact case-sensitive methods, optional query strings, root `/`.
  It rejects percent escapes, repeated separators, dot-segments, wildcards,
  and malformed parameter names. A future URL decoder must handle all path
  canonicalization and security checks *before* enabling richer routing.
* Checked metadata objects **are not capabilities**. They do not own sockets,
  streams or authority; they do not imply that body content has been validated.
* The still-unimplemented `get` / client / server / body stream APIs require
  audited, separately authenticated tenant and actor network permissions, DNS
  and TLS, cancellation, bounded memory, redirect reauthorization, and
  async non-parking transport integration. Never return fake successful I/O.
* No reflection or dynamic dependency discovery is required, so the pure
  `.atto` policy layer is suitable for AOT once actual guest compilation passes.

The guest parity fixture now has **66** executable expressions
(18 existing plus 48 new), including malformed status,
Content-Length overflow, CRLF injection and route-normalization cases.
As of this edit these tests are **NOT_EXECUTED**: the source-org exact-head
workflow has failed with zero admitted steps. Do not interpret this as a pass.

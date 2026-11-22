# std.crypto — portable encryption and cryptographic services, v1

## Status

This repository owns its own `stdlib/std/crypto.atto` source. The `.atto` functions in this first slice are **pure admission policy**,
not a complete guest-callable encryption implementation.
The compiler now admits only the exact
`import * as crypto from "std.crypto";` form for the built-in
`crypto` namespace. It is deliberately resolved without arbitrary host
classes, files or package identity. The `.atto` admission-policy source
is still independent of these host intrinsic functions; **the full
standard-library module has not been compiled or executed as a guest**
and this PR is not a released encryption implementation.


### Java trusted-host progress (this PR)

`src/main/java/dev/oreslang/runtime/AeadPrimitives.java` now implements
AES-256-GCM and IETF ChaCha20-Poly1305 using JCA ciphers (not
handwritten primitives). Fresh opaque keys cannot be imported or exported;
per-key 4-byte random prefixes plus synchronized 64-bit counters reserve
unique 12-byte nonces. Handles implement explicit `close()` key scrubbing.
Ciphertexts include a full 16-byte tag and are only returned after
authentication. Bad tags/wrong key/wrong AAD produce one error.

Host-only Java 21 smoke compilation and execution passed for both ciphers,
round trips, repeated unique nonces, tampering, wrong AAD/key/nonce,
zero-length messages, defensive output copies and closed handles. The
retrieved Git blob hash of the tested source was
`44bbd171b86a22563df4dbdc382aee7542cda758`. The repo also has
`AeadPrimitivesTest.java` JUnit tests, but the **JUnit project suite and
actual guest compiler, importer and AOT tests remain NOT_EXECUTED**.
This host primitive has a first compiler-owned guest-callable prototype, but full guest execution is unverified; its opaque key handle
will need actor-lifecycle ownership, no-log, capabilities and memory gates
when integrated. Avoid claiming it is a released `std.crypto` API.

## Java guest intrinsic prototype (current branch)

The Java evaluator now recognizes a compiler-owned **`crypto` namespace**
in guest source. This is deliberately separate from a validated file import:
`import * as crypto from "std.crypto";` is now statically admitted
as an exact compiler-owned import. Arbitrary aliases, selected function
imports, lookalike paths, duplicate crypto imports, and crypto calls
without an explicit import are rejected by the static checker.
This remains an **unexecuted compiler integration** pending JUnit. This is an initial intrinsic
namespace, **not** source-linked `.atto` function execution or proof of
a working AOT linker.

Working implementation targets in this PR:

```atto
import * as crypto from "std.crypto";

val key = crypto.aead_new_key(1);   // 1=AES-256-GCM, 2=ChaCha20-Poly1305
val sealed = crypto.aead_seal(key, [1], [65, 66, 67]);
val envelope = sealed.unwrap();      // [12-byte nonce | ciphertext | 16-byte tag]
val opened = crypto.aead_open(key, [1], envelope);
crypto.aead_close(key);
```

`aead_new_key` produces an opaque, non-copyable `AeadKey`.
`aead_seal/open` accept a reusable borrowed key and return
`Result<List<int>, String>`; `aead_close` consumes its key.
`String` is a **temporary** error payload until `CryptoError` and the
checked error ABI are integrated. AAD and plaintext are currently represented
as byte-valued `List<int>`, with a stricter **1 MiB guest message limit**
to bound `List<Long>` allocation overhead. Native implementation has a
16 MiB primitive maximum; the two must not be confused.
`aead_open` returns `Err("authentication failed")` for malformed
envelopes and bad tags/AAD, and never exposes unauthenticated plaintext.

The guest interface is capability-gated through `CRYPTO`, granted in the
developer isolate profile but **not** by strict/adversarial profiles.
Opaque keys belong to the exact context and actor incarnation. A rejected
cross-context or cross-actor handle cannot decrypt. Context shutdown seals
new key admission before quiescence and scrubs tracked keys **after** actor
and native-I/O physical joins; on failed quiescence keys remain quarantined
with the context rather than being freed while a carrier may still run.
The key registry permits up to 1,024 live keys per context and 32 per
actor incarnation/root owner. Explicit close releases that quota.
Actor-specific early retirement remains future work.

The opaque key has no import/export or persistence facility. Per-key
random four-byte prefixes with synchronized 64-bit monotonic counters
ensure unique nonce reservations **within one freshly generated key**, with a 2^32-encryption-per-key cap.
Because the key is lost on process termination, this first prototype
does not require persisted counter replay protection; persistent keys
will require crash-safe nonce reservation before any durable API.

`HkdfSha256.derive` is now a trusted Java host implementation of
RFC 5869 using the platform `HmacSHA256` provider with bounded work
and transient secret-buffer scrubbing. The guest API intentionally does
not expose raw HKDF secret output until an owned, zeroizing `SecretBytes`
type exists.

**Executed checks, not guest parity:** isolated Java 21 `javac` +
direct runtime smoke passed AES-256-GCM and ChaCha20-Poly1305 round trips,
nonce uniqueness, wrong key/AAD/nonce/tag and key close on the exact
`AeadPrimitives.java` blob
`44bbd171b86a22563df4dbdc382aee7542cda758`.
Isolated Java 21 `javac` + direct runtime RFC 5869 test case 1
and output-limit rejection passed on exact `HkdfSha256.java` blob
`27dec43d6c60478f36d8e8cb551d424e572bd6f1`.
New `CryptoAeadGuestSourceTest`, `HkdfSha256Test` and
`AeadPrimitivesTest` have **NOT been executed** via repository Maven,
the real Atto interpreter, GraalVM 25/Native Image, LLVM, .NET or Wasm.
The separate Java SHA/HMAC/PBKDF2 PR #1367 remains unmerged and requires
manual integration, not a duplicate implementation.

## Portable Atto source envelope helpers

The additional `stdlib/std/crypto_envelope.atto` file is implemented
in this compiler's own Atto source tree. It defines pure, overflow-safe
admission and offsets for **nonce[12] | ciphertext[n] | full_tag[16]**;
minimum valid envelope length 28 bytes and maximum 16 MiB + 28.
A negative sentinel indicates malformed lengths and MUST be checked
before indexing. The algorithm and key identity are **not** embedded
in this v1 envelope and must be authenticated by the higher-level
protocol; do not treat the payload as self-describing or derive a key
from unauthenticated metadata.

`tests/fixtures/crypto_envelope_v1.atto` covers min/max, truncated
tags, AAD budget, ciphertext length and tag offset. **Neither the
envelope fixture nor the existing `crypto_policy_v1.atto` has been
executed by a full Atto stdlib source linker yet**. Host-only crypto
tests do not satisfy that missing guest conformance proof.

## Namespace-first Atto standard library layout

The source declaration in this repository is **`use namespace crypto;`**,
not `define module crypto` and not a dotted namespace declaration.
The Atto parser currently accepts a flat namespace header; public library
identity and import paths are resolved separately:

```atto
// stdlib/std/crypto.atto or stdlib/std/crypto_envelope.atto
use namespace crypto;

// in a consumer source file
import * as crypto from "std.crypto";
```

Two separately maintained `.atto` files can contribute distinct pure
functions to the `crypto` source namespace; namespace metadata alone does
**not** prove a multi-file linker correctly merges them. The Java compiler
currently treats the exact `std.crypto` import as an intrinsic namespace
for its supported crypto host methods, not yet as a verified source export
graph for `crypto.atto` and `crypto_envelope.atto`. LLVM, .NET and Wasm
still require their own namespace import-resolution and guest source tests.

For the **strict Atto source profile**, comparisons in these files and
fixtures use `eq` / `neq`; legacy `==` / `!=` must not be reintroduced.
Every compiler can own different `.atto` implementation files while
maintaining the agreed public AEAD envelope and error contract. No shared
canonical source dependency is required.

## Public API to implement

- `hash.sha256(bytes) -> Bytes` and `mac.hmac_sha256(secret, bytes) -> Bytes`.
- `kdf.hkdf_sha256(ikm, salt, info, output_bytes) -> SecretBytes`.
- `kdf.pbkdf2_hmac_sha256(password_bytes, salt, iterations, output_bytes)`
  for bounded protocol compatibility only; Argon2id is a later audited
  password-storage service, not a substitute for HKDF.
- `random.secure_bytes(n) -> SecretBytes` and `random.key_256() -> SecretKey`.
- `aead.aes_256_gcm.seal(key, aad, plaintext) -> Sealed{nonce,ciphertext}` and
  `aead.aes_256_gcm.open(key, nonce, aad, ciphertext) -> Result<Bytes, CryptoError>`.
  Sealing reserves a nonce through the opaque key handle; callers do not
  choose a nonce for managed encryption.
- `aead.chacha20_poly1305.seal/open(...)` with equivalent authenticated
  semantics where vetted support exists.
- `constant_time.equals(left, right) -> bool` implemented by backend code,
  not a guest-language early-return byte loop.

These are **target API signatures**, not runnable exports in this first PR.
The current usable pure functions are the named predicates in `crypto.atto`.

## Wire and security contract

Algorithm ids are 1 = AES-256-GCM and 2 = IETF ChaCha20-Poly1305.
Both require 32-byte keys, **unique 12-byte nonces for each key**, and
**full 16-byte authentication tags**. AAD is authenticated but not encrypted.
Ciphertext size in this proposal is plaintext length + 16 tag bytes
(without any nonce or envelope prefix); any future envelope uses an explicit
version, algorithm, nonce, and lengths, without silently altering this shape.
Do not expose an unauthenticated cipher as an encryption convenience API.

Use an internal nonce manager with per-key counters and crash-safe
reservation, or another reviewed nonce-generation strategy. A random nonce
by itself is not an unlimited uniqueness guarantee. Never auto-generate a
nonce with an untrusted/noncryptographic RNG or let untrusted code seed the
CSPRNG. Keys are opaque, move-only or sealed handles with no accidental
serialization/logging and explicit zeroization on retirement where possible.
No secret-dependent branching or timing guarantees are made by policy
predicates; trusted backends must supply those guarantees.

Open authenticates **before** returning any plaintext. Tampering, unknown
key, bad tag and wrong AAD produce the same externally observable
authentication failure. All error paths drop buffers and wipe secret
temporary memory where possible. Enforce permission/effect and memory
quotas again in the host; validation functions are advisory and cannot
authorize a resource operation.

Per-call budgets: 16 MiB message, 64 KiB AAD, 1 MiB HMAC key/IKM,
HKDF-SHA-256 output ≤ 8,160 bytes (RFC 5869), bounded PBKDF2 work
≤ 100,000 rounds **for protocol compatibility**, entropy requests ≤ 4,096
bytes. Higher-cost password storage must use a separate async/memory-hard
and explicitly budgeted API.

## Consolidated SHA/HMAC/PBKDF2 implementation

The existing Java PR #1367 `CryptoPrimitives.java` and
`CryptoPrimitivesTest.java` were incorporated into this branch as
**byte-identical Git blobs**, hashes
`b1243dbe5a1cfa8ac6b0a101a264f64585720015` and
`8819155e9006a6e4892af24b4cbf1a910ea3626a`. The evaluator
and type/ownership checkers now expose its SHA-256, HMAC-SHA-256,
bounded PBKDF2, secure randomness, canonical Base64, and
constant-time comparison under the same trusted `crypto` namespace.
No independent duplicate primitive code was written.

An isolated Java 21 direct smoke of the exact integrated
`CryptoPrimitives.java` Git blob
`b1243dbe5a1cfa8ac6b0a101a264f64585720015` compiled
and **passed** SHA-256("abc"), HMAC-SHA-256("key","abc"),
PBKDF2-HMAC-SHA-256("password","salt",4096,32), canonical
Base64 decoding, secure-random output sizing, equality checks
and an excessive-PBKDF2-cost rejection. This test did not
load or execute an Atto guest compiler.

Guest `List<int>` message inputs are further limited to 1 MiB
in the Java facade to contain boxed-list overhead, even where the
trusted Java primitive accepts 16 MiB. SHA/HMAC outputs are returned
as byte lists; password/secret material in these lists still awaits
a dedicated zeroizing `SecretBytes` type. HKDF is intentionally
host-only until this type exists. Do not call the combined API
production-ready for storing high-value keys.

JUnit source tests `CryptoMergedGuestSourceTest`,
`CryptoImportAdmissionTest`, and `CryptoAeadGuestSourceTest`
cover expected vectors, malformed byte and KDF limits, import path
admission, authenticated open/close and capability denial.
**They are not yet executed on this compiler head.** Root import
and cross-backend negative semantic proofs are still required.

## Implementation ownership and rollout

1. Keep this compiler's `.atto` code local. Separate compilers may use
   different algorithms for policy, memory layout and host library bindings
   while preserving public protocol behavior and test vectors.
2. Bind import `"std.crypto"` and guest types to the compiler; map
   trusted primitives to platform vetted implementations. Java can reuse
   its existing SHA/HMAC/PBKDF2 draft PR #1367 (where applicable);
   other backends need independently authenticated implementations.
3. Add AES-256-GCM and ChaCha20-Poly1305 behind capabilities and
   ownership-safe key/nonce types. Do not implement AES/GCM/Poly1305
   manually in a new guest loop as a stopgap for vetted primitives.
4. Execute `tests/fixtures/crypto_policy_v1.atto` with this compiler
   after importer integration, then run NIST/RFC known-answer vectors,
   tamper/wrong-key/wrong-AAD tests, random quality integration checks,
   arena scrub/lifetime tests, cancellation and zero-GC/AOT admission.
   Record the exact head SHA and steps; source-only checks and
   zero-step Actions are NOT_EXECUTED for guest conformance.

References: RFC 8439 (ChaCha20-Poly1305), RFC 5869 (HKDF),
NIST SP 800-38D (AES-GCM). NIST's current 38D revision work does
not justify weakening nonce-uniqueness or tag requirements.

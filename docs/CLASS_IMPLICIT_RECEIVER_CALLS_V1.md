# Class and actor implicit receiver calls — draft v1 contract

Status: Java source implementation on a draft branch. **Not verified for release or backend parity.**

## Decision

Inside an instance method, a bare direct invocation `roodles(a)` resolves as
`self.roodles(a)` **only** when there is no lexical binding called `roodles`
and the receiver's class or ancestors declare that *instance method family*.
Instance-method families win over same-named file/module functions. A wrong
arity or inaccessible method produces the ordinary method error; it MUST NOT
fall back to a global function. Lexically bound callable locals/parameters
always win. Static class functions, file-level callables and constructors do
not acquire an implicit receiver.

This is a **direct-call convenience only**. Unqualified field reads/writes are
not introduced. Names used as values never bind `self`. In particular:

```ores
define class Foo as
  pub roodles(): void { return; }

  pub park_it(): void {
    self.roodles();              // direct invocation; valid
    roodles();                   // direct invocation; same receiver and method slot

    val r = self.roodles;        // ERROR: no implicit bound method value
    val s = roodles;             // NOT an implicit bound method value
  }
end
```

A method value that intentionally captures a receiver requires an explicit
lambda with permitted lexical capture, such as `lex[self] || -> self.roodles()`
where the capture/borrow checker admits the receiver and its lifetime.
`lex[self]` only permits *lexical visibility*, not a move, send, lifetime
extension, actor-state access or callback escape. An ordinary lambda has no
implicit `self` capture. Actor `self` remains a non-escaping mailbox
capability. Nested critical state loans must never be retained by a closure.

## Receiver binding and performance

Each class/actor code image holds shared method implementations and immutable
dispatch slots. Instances hold their own state and class identity, not cloned
bound callbacks. Invocation passes one receiver into the called method frame,
where `self` has its checked receiver-polymorphic type. No bound-method
allocation is required for `roodles()` or `self.roodles()`.

`method(self, T x)` and `method(T x)` define exactly the same method slot:
the receiver does not contribute to caller-visible arity. A mutable receiver
requires a valid exclusive borrow. All inheritance, visibility, overrides,
effect annotations, suspension rules, method overload selection and actor
isolation remain identical between the two call spellings.

Constructor self is incompletely initialized, so an implicit method call from
the constructor must fail exactly as an explicit `self.method()` call fails
until transitive constructor-safe effect summaries exist.

## Implementation gates

1. Parser accepts the two direct-call spellings without rewriting field names.
2. Type checker resolves the same method family, lexical shadowing, overload
   arity, inheritance and visibility for both spellings.
3. Ownership checker must resolve the same method **before** checking loans,
   suspension, mutation, actor state and constructor restrictions.
4. Runtime must never manufacture an instance-bound callback on ordinary
   class member extraction; direct instance calls dispatch through the shared
   method table. Static function values remain a separate concept.
5. Test nested calls, generic/async methods, late-bound overrides, overload
   arity mismatch, local function shadowing, actor self, no implicit closure
   capture, and constructor partial-self rejection.
6. Verify exact Git tree SHA through JVM unit/guest tests and GraalVM native
   image, then replicate with real compiler evidence on LLVM, .NET, Forge and
   Wasm. A source-only patch or zero-step Actions run is not a passing test.
7. Private CI copies are forbidden unless the exact destination repository,
   numeric ID and reviewed carrier contract have been approved independently.

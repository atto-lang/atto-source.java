# Explicit omission, type-level Option sugar and named defaults (v3 proposal)

Status: **language-design draft, not executable syntax**. This is a proposed revision of the staged v1/v2 optional-parameter contracts in Java PRs #1334/#1335 and LLVM PR #623. Do not enable it by partially reinterpreting the old source grammar. Named required-call execution is staged separately in Java PR #1508.

## Semantic decision: two independent question marks

The `?` after a **complete type** is a type operator: `T?` is exactly the intrinsic nominal `Option<T>`; it DOES NOT permit a missing call argument. The `?` immediately after a **parameter name** declares an omittable call slot; it DOES NOT change the body-visible parameter type.

| Formal | Body type | Omission without annotated default |
| --- | --- | --- |
| `int a` | `int` | error |
| `int? a` | `Option<int>` | error |
| `int a?` | `int` | declaration error: missing default |
| `int? a?` | `Option<int>` | inject typed outer `None` |
| `Option<int> a` | `Option<int>` | error |
| `Option<int> a?` | `Option<int>` | inject typed outer `None` |

Type-level `T?` applies only in an explicitly versioned v3 module; other contexts (including structural tuple optional tails) need separate migration. In v3, `Option<T>` does **not** automatically make a parameter omittable. No `required Option<T>` escape hatch is needed. Do not reinterpret v1's optional-formal `T? a`, or v2's implicitly omittable `Option<T> a`, silently at module imports.

## Defaults via a declaration annotation

~~~oreslang
@DefaultArgVals(a: 5, b: "charlie")
pub fnc run(int a?, string b?): void { }
run();                   // (5, "charlie")
run(b: "delta");         // (5, "delta")
run(a: 8);               // (8, "charlie")

// Annotation keys alone also grant omission, even without a name-suffix ?:
@DefaultArgVals(a: 5, b: "charlie")
pub fnc run2(int a, string b): void { }
run2();                  // (5, "charlie")

// T? changes the type, NOT the omission mechanism:
@DefaultArgVals(a: 5, b: "charlie")
pub fnc maybe(int? a, string? b): void {
    // a: Option<int>; b: Option<string>
}
maybe();                 // (Some(5), Some("charlie"))
maybe(a: None);          // (None, Some("charlie"))

pub fnc inspect(int? a, string? b?): void { }
inspect(None);           // (None, None)
inspect(None, "x");      // (None, Some("x"))
inspect();               // ERROR: a is required
~~~

`@DefaultArgVals` is a compiler-owned declaration annotation, not a runtime decorator and not a user-defined function call. Keys are exact case-sensitive **formal names**, not positional indexes. A key marks its formal omittable, and optionally its `name?` may repeat that fact; this is not an error. The prior staged per-formal `@Default(v) T name` remains a canonical equivalent after normalization. Mixing `@Default` and `@DefaultArgVals` for the same formal is an error, even with equal values. Missing/unknown/duplicate keys, duplicate annotations and defaults for `self`, rest, borrowed or mutable formals are rejected.

First release defaults are strictly closed, immutable, compile-time scalar constants: integer (checked for range), bool, string and `None` (only for intrinsic Option). No expressions, reads, effects, allocations of mutable defaults, interpolated strings, user-defined Option imitations, or `null`/`undefined`/`void`. For an Option<T> formal, a T default yields `Some(T)`; `None` yields typed outer `None`. For a non-Option T formal, default must be exactly T. For nested `Option<Option<T>>`, only a correctly typed explicit outer Option value or the outer `None` may be admitted until lifting proof exists.

An omittable non-Option T MUST have an explicit default. `pub fnc f(int x?)` is rejected at declaration time, not filled with a zero, `null` or an uninitialized value. An omittable Option<T> can omit with no default, producing None. Explicitly supplied typed `None` always wins over any annotated default.

## Call binding and omission

Positional arguments bind sequential formal slots without implicit shifting. To skip a non-trailing slot use named arguments or a standalone `_` / `omit` call item. `f(x: _, y: effect())` omits x, evaluates only y; `f(x: None)` provides a real typed Option value. Omission items are NOT expressions, do not have side effects, and must have exact source origin preserved.

Once a named item appears, positional items are forbidden afterward (except a separately checked final anonymous closure under the narrow #1492 contract). Reject duplicate labels, unknown labels, positional+named collisions, tuple/collection spreads without typed proof, or omission of a required slot. Missing required parameters fail before any caller expression executes.

Resolved signatures and checked binding plans must preserve:
- **independent** resolved type, omission permission, normalized typed default (if any), formal name/position and explicit omission provenance;
- receiver/callee-first, then all PROVIDED source expressions exactly once in lexical source order, then compiler-owned constant/default and Option packaging into **declaration-order full ABI arity**;
- original call-item indexes, dense evaluated-expression indexes, owner/method identity, generic substitutions, borrowing/move/effect proofs and ABI version;
- override/interface/import signatures with identical parameter names, type, requiredness and defaults; no runtime label map or virtual default re-selection.

For an Option<T> formal with omission enabled (`name?` or a default), supplied T may be lifted once to Some(T), or an exactly typed Option<T> passed unchanged. A required Option<T> formal accepts a typed Option<T>, not an implicit T. Reject ambiguous nested lifting, user-defined Option lookalikes and type inference from missing arguments.

Changing any public omission or default metadata changes its **call-contract fingerprint**, even when the physical full ABI arity stays the same. Defaults must be serialized with imported signatures (not rederived from local declaration text). Use a feature/version gate and negative tests for old v1/v2 imports; do not globally flip v1/v2 meanings.

## Receiver and actor-method interaction

For every class/actor instance method, the hidden receiver is NOT a defaultable
formal, regardless of declaration spelling. `foo(int x)` and
`foo(self, int x)` select the same caller-visible arity 1 slot and conflict.
The two-list receiver spelling `foo(self Foo)(int x)` also selects that
same slot. The explicit receiver's type/mutability is separately checked.

Defaults bind only formal VALUE slots, after receiver erasure. For example:

~~~oreslang
define class Foo as
    @DefaultArgVals(x: 5)
    pub foo(self, int x?): int { return x; }

    pub bar(): int {
        return self.foo();            // optional x defaults to 5
        // self.foo(self, 5) is a two-user-argument call, NOT a receiver spelling
    }
end
~~~

A provided `self` inside the argument list always counts as one supplied
value for overload admission and must satisfy the formal's type/ownership
checks. No default, named label, omission marker or receiver alias may consume
this expression or smuggle it into the hidden receiver slot. Actor-owned self
must not cross a mailbox or unsafe actor capability boundary. See the separate
parser implementation PR #1513 and `contracts/v3/SELF_RECEIVER_ARITY_V1.md`.

## Overload and constructor safety

Overload identity remains caller-visible value arity (constructor's leading `self` is excluded). For the first implementation, reject entire overload families with intersecting conservative effective supplied-count intervals. A default or `name?` lowers the minimum required count; labels, return types, erased generic types and declaration order NEVER select an ambiguous overload. Preserve any separate nominal enum exception only after an explicit proof that omission cannot introduce collisions.

Ordinary explicit constructors use the same binder/default checks, constructor self isolation and exact-once immutable field initialization. Actor constructors remain forbidden. Unknown/opaque function values and unverified generic/foreign/dynamic targets fail closed on named, omitted or defaulted calls until stable static signatures are available.

## Implemented parsing milestone (October 19, 2026)

Java draft PR #1512 now recognizes the **closed literal** `@DefaultArgVals(a: 5, b: "charlie")`
annotation in the ordinary AST. Its parser requires parentheses, colon-separated
distinct keys and int/bool/string/`None` constants (including signed negative
integers). It rejects malformed, nonliteral and duplicate-key annotations,
then preserves named constants as `Ast.Annotation` metadata.

Critically, `UnloweredDefaultArgValsGuard` rejects any staged declaration
before normal `TypeChecker.collect` and at direct `OresEvalRootNode` construction.
Parser acceptance here is NOT an executable language feature, and the
`T?` type/parameter distinction and omission ABI are still TODO.
The new regression tests are committed source, NOT an executed Maven/JUnit pass.

## Existing implementation status and hardening gates

- Java #1335 already stages per-formal `@Default(literal)` parser/AST and a pure `DefaultArgumentPolicy`, but blocks unlowered guest execution; it currently models a **different** T?/Option omission contract.
- Java #1508 stages executable required named calls only; optional/default/rest integration is not present. LLVM #623 is a pure optional contract; LLVM's exact-arity named scalar subset is not equivalent support.
- This v3 proposal intentionally changes a staged contract, not shipped semantics. Do not quietly modify AST parsing, type checking or any backend until same fixture corpus is adopted by Java/LLVM and the versioned migration is approved.

Required evidence: parser/formatter roundtrips; nominal Option and alias/generic resolution; unknown/duplicate/default typing negatives; call-item provenance and source-order effects; closures/constructors/actor gates; override/linker fingerprint drift; overload interval collisions; move/borrow/cancellation/exception ordering; interpreter/Graal AOT and LLVM -O0/-O2 exact-tree guest positive+negative tests. A green reference policy alone is not compiler/backend proof.

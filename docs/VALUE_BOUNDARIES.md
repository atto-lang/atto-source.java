# Value, call and suspension boundaries

Audited against the Java reference implementation on 2026-10-15. The other
source backends implement smaller subsets; rejection is not feature parity.

## Records, structs, tuples and pairs

A record type describes named fields; a struct expression constructs a value
with a closed field shape. Field order is **not** part of structural type
identity: `{left:int,right:string}` and `{right:string,left:int}` describe the
same shape. Access and object destructuring use names, never field positions.
Initializers evaluate in source order. The interpreter's `LinkedHashMap`
storage is not an ordered-key reflection API or a portable layout guarantee.
There is no built-in general Map/OrderedMap or Pair/Entry API in this frontend.

Tuples are finite positional values. Their element order and arity are part of
the type. A two-element tuple is a suitable positional pair; a named entry can
be a record with `key` and `value`. These are explicit representations, with no
automatic record-to-tuple, record-to-map or nominal Pair conversion.

```ores
fnc pair(): [int,int] { return (19,23); }
fnc entry(): {key:string,value:int} {
  return infer struct{key:"answer",value:42};
}
fnc add(int a,int b): int { return a+b; }
fnc consume({key:string,value:int} item): int {
  const {value,key} = item;
  return value;
}
pub fnc main(): void {
  const [a,b] = pair();
  const {value,key} = entry();
  stdio.stdout.write(add(a,b));
  stdio.stdout.write(add(...pair()));
  stdio.stdout.write(consume(entry()));
  return;
}
```

Keep ordinary maps semantically unordered when a general map API is added.
A separate ordered-map implementation may promise insertion order, but that
promise must be explicit and tested; an implementation using ordered host
storage must not accidentally establish the contract for every map.

## Indexing and argument expansion

`(5,3,1)[0]` evaluates to `5`; `[5,3,1][0]` also evaluates to `5`. Indexing is a
postfix operation on the complete parenthesized tuple. `(5)` is grouping;
`tuple(5)` is a one-element tuple and `tuple()` is empty. Commas within a tuple
do not separate the enclosing call's arguments. Nested and chained indices
retain this rule.

Literal tuple indices retain the exact slot type and reject statically known
out-of-range indices. Dynamic tuple indexing and `.get(i)` retain the union of
possible slot types. A homogeneous tuple therefore still produces its element
type. A dynamic string key on a closed record likewise produces a union of
possible field types. Runtime indexing checks bounds; host floating-point
indices are rejected rather than truncated, for reads, writes, `.get` and
`.set` on both synchronous and suspendable execution paths.

`f(...tuple)` expands a statically known tuple into positional parameters and
checks arity, each slot type and ownership. `arguments` is a fixed tuple in
declaration order. A runtime-length array/list cannot fill a fixed parameter
list; neither can a record. Destructure an array and pass selected values
explicitly when the intended arity is known. There is no callable `.call`
method; unknown members of a statically typed function are compile-time errors.

## Inline condition captures and patterns

A capture is a value-preserving expression in an `if`/`while` condition:

```ores
if is_ready(some_call() as let value) {
  consume(value);
} elif other_condition {
  handle_other();
fi

if (some_call() as let value) > 0 then
  consume(value);
fi

while is_ready(some_call() as let value) {
  consume(value);
}
```

Each reached capture evaluates its initializer once, binds an ordinary `let`,
and yields the same value to its surrounding expression. Calls and arguments
keep their normal evaluation order. The complete condition must be bool; a
false or zero payload can still satisfy an explicit predicate. `as Type`
remains a checked cast, and `>>` remains bit shift. There is no semicolon binding
form. Parentheses delimit captures used as comparison operands or receivers.

The capture's lexical scope is the remainder of that condition and its matching
body. It is unavailable in sibling `elif`/`else` branches or after the `if`.
Each loop test has a fresh binding, including after `continue` and on the final
false test. Captures on the right of short-circuit operators or in conditional
arms are rejected until path-sensitive binding admission is implemented. A
capture inside a lambda body is not part of its enclosing condition.

Capturing uses the ordinary binding ownership checker. Moving an owner into a
capture invalidates the source on both true and false paths. Passing the
captured owner by value moves it; the body cannot then reuse it. To inspect an
owner and consume it in the body, explicitly borrow it for the predicate:

```ores
if is_ready(rt borrow (make_item() as let value)) {
  consume(value);
fi
```

The predicate's temporary loan lasts through the call and ends before the
body. Capturing a borrow instead retains its original owner's lexical loan
through the body. That loan prevents conflicting moves/mutable borrows and
suspension, and cannot escape via returns, storage, or closures. `let` permits
rebinding but grants no mutable referent capability. Mutable projected borrows
and guard-bearing `let` captures remain rejected by existing rules. In a loop,
repeatedly moving an outer owner is rejected; producing a fresh owner is valid.

Refutable condition patterns reuse existing pattern-test ownership rules:

```ores
if let Some(value) = maybe_call() then
  consume(value);
fi
if let Some(value) = maybe_call() && value > 0 {
  consume(value);
fi
```

This tests the constructor, not payload truthiness: `Some(false)` and `Some(0)`
both match. Pattern bindings exist only on the matched path and are aliases
with the existing ownership restrictions, not unchecked duplicate owners.
Bare `if let value = expr` and wildcard-only heads are rejected. The same
pattern head is accepted by `while`.

The earlier explicit `expr as let value when predicate` head remains accepted.
It lowers to an ordinary binding followed by the conditional; unlike expression
captures, its binding covers the remaining branch chain (including `else`).
Use the expression form when branch-local scope is desired.

`{` can replace `then`; `} elif` / `} else` separate braced branches, and `fi`
closes the final branch and the entire conditional. The historical `} fi`
spelling remains accepted with a migration diagnostic. Braces for functions,
loops, lambdas and explicit blocks close normally with `}`.

## Async and suspend boundaries

Java async callables return Ores-owned futures. `async` return shape and
`suspends` effect admission are separate contracts. Scheduler await unwinds the
current turn and resumes through a fresh dispatch, including for a future that
is already settled. Runtime completion callbacks enqueue work; they must not
run guest continuations on producer or I/O threads. This applies to success,
failure and cancellation, and preserves owning execution-context admission.
A later turn may reuse the same carrier thread; thread identity is not the
execution context. Cancellation does not by itself prove a guest turn exited.

The callback timing matrix and existing scheduler, await and suspend suites
exercise these rules. This audit does not establish native-image parity or
source-level async support in LLVM, Wasm, .NET or Forge. LLVM's native completion
race primitive is infrastructure, not source await lowering.

## Strings and template variables

Single- and double-quoted strings are literal strings with escape processing.
`"${value} $value {value}"` retains those characters; it does not read `value`.
Backtick templates and template variable interpolation are not implemented.
Backtick syntax is rejected. A future interpolation feature needs an explicit
syntax and typed formatting/effect rules, including evaluation order, escaping,
unknown names, nested delimiters, ownership, and suspension; substituting source
text or running dynamic evaluation is not an implementation of that contract.

## Regression coverage

`ValueBoundaryAuditTest` executes indexing, returned aggregates, named record
arguments, tuple spread, inline conditions and host-index rejection.
`CaptureExpressionAuditTest` covers capture evaluation, scopes, patterns, moves,
borrow lifetimes and suspension. `CallbackSchedulingAuditTest` tests ready/later success, failure and cancellation
with fresh dispatch and owning-context assertions. Existing destructuring,
structural-record, scheduler, async and suspend suites remain required.

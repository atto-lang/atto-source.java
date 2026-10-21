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

## Inline condition bindings

```ores
if some_call() as let value when value > 0 && value < 10 {
  consume(value);
} fi

while some_call() as let value when value > 0 {
  consume(value);
}
```

`some_call()` may return any admitted value type; the `when` predicate must be
boolean. There is no implicit truthiness. `as let` is distinct from the existing
checked cast `as Type`; `>>` keeps its bit-shift meaning. Parenthesized condition
heads and the `if ...; then ... fi` spelling are supported. `fi` remains required
for brace-form `if` too.

Each reached initializer evaluates once before its predicate. An `elif`
initializer runs only if earlier tests fail. The binding has normal `let`
rebinding semantics and does not grant mutable access to the referent. Its
lexical scope covers its predicate, branch, and remaining `elif`/`else` chain,
but not statements after the whole `if`. This includes a false predicate's
`else` branch. An `elif` binding is unavailable to earlier branches.

A `while` binding is recreated before every predicate test, including after
`continue`. Its initializer and predicate also run on the final unsuccessful
test. The binding does not escape the loop. `break`, `return`, suspension,
ownership and defer continue through the existing checked statement machinery.

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
`CallbackSchedulingAuditTest` tests ready/later success, failure and cancellation
with fresh dispatch and owning-context assertions. Existing destructuring,
structural-record, scheduler, async and suspend suites remain required.

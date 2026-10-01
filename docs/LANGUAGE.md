# Oreslang language design (v0)

Oreslang is a statically typed, structurally typed language implemented on Truffle for GraalVM. The design favors explicit mutation, actor heap isolation, predictable message passing, and a stricter isolate profile for untrusted/FaaS execution.

## 1. Source files and modules

A source file may contain more than one named module:

```ores

define module foo
  pub fnc answer() => int {
    return 42;
  }
end

define module bar
  fnc hidden() => void {
    return;
  }
end
```

A module name is mandatory. Top-level functions are private unless prefixed with `pub`.

## 2. Bindings

Every variable binding is declared with exactly one of:

- `const`: compile-time constant. The initializer must be compile-time evaluable.
- `val`: runtime single-assignment binding. The binding cannot be rebound.
- `let`: mutable binding.

Examples:

```ores
const max_retries = 3;
val request_id = process.request_id;
let attempts = 0;
```

Types may be inferred or written explicitly:

```ores
let int attempts = 0;
val String name = "ores";
```

Destructuring places the binding mode on each element, not on the container pattern:

```ores
[const code, let body] = get_tuple();
[val head, let tail] = get_list();
```

Oreslang intentionally does not use `const [a, b] = ...` because each binding can have different mutability.

## 3. Functions

Functions use `fnc`. They are private by default:

```ores
fnc add(int a, int b) => int {
  return a + b;
}

pub fnc parse(String input) => Result<Value, ParseError> {
  return parser.parse(input);
}
```

Only one value may be returned. To return several logical values, return one tuple, array, record, class value, or another aggregate.

Every non-`void` path must execute an explicit `return`. Oreslang does not use implicit final-expression returns.

Return types may be written with a fat arrow or with a return annotation:

```ores
fnc id<T>(T value) => T {
  return value;
}

@Ret<int>
fnc answer() {
  return 42;
}
```

The two return syntaxes are equivalent. Supplying both is allowed only when they denote the same type.

## 4. Classes and receiver methods

Classes use `define class`:

```ores

define class Box<T>
  val T value;

  @Ret<self>
  identity() {
    return self;
  }

  get() => T {
    return self.value;
  }
end
```

Methods do not use `fnc`. Every instance method has an implicit receiver named `self`.

An explicit receiver form is also accepted when the receiver type should be visible in the signature:

```ores

define class x
  find(self x)(int foo) => self {
    print(self);
    return self;
  }
end
```

The receiver binding is always named `self`; another receiver variable name is a compile-time error. `find(self x)()` and `find()` are equivalent for receiver binding purposes.

`self` is valid as a return pseudo-type and resolves to the enclosing class type:

```ores
@Ret<self>
find() {
  return self;
}

@Ret<x>
copy() {
  return new x();
}
```

## 5. Structural typing

Interfaces and record-like values are structurally assignable. A value satisfies a target structural type when it exposes every required member with compatible types; extra members are allowed.

```ores

type Named = {
  String name;
};

define class User
  val String name;
  val int id;
end

fnc greet(Named n) => String {
  return "hello " + n.name;
}
```

`User` is assignable to `Named` without an explicit declaration because it has a compatible `name` member.

`impl` remains useful to request compiler checking and documentation, but it is not required for structural compatibility.

## 6. Generics

Types, classes, interfaces, and functions may be generic:

```ores
fnc id<T>(T value) => T {
  return value;
}

define class Pair<A, B>
  val A first;
  val B second;
end
```

`Array<T>`, `Map<K,V>`, `Future<T>`, `Result<T,E>`, tuples, function types, and user-defined generic types are first-class.

`Array<>` is permitted only in an inference position. Public/exported API signatures must resolve every inferred generic before module export.

## 7. Numbers

Oreslang has separate integral, floating-point, decimal, and complex numeric families.

Initial built-in numeric types:

- integral: `i8`, `i16`, `i32`, `i64`, `u8`, `u16`, `u32`, `u64`, `int`, `uint`, `bigint`;
- floating: `f32`, `f64`, `float`;
- decimal: `decimal`;
- complex: `complex64`, `complex128`, `complex`.

Examples:

```ores
const int count = 3;
const f64 ratio = 0.25;
const complex z = 3 + 4i;
```

The imaginary suffix `i` creates an imaginary numeric literal. Arithmetic between real and complex values promotes to the appropriate complex type. There is no silent lossy narrowing.

## 8. Lambdas

Lambdas use a single arrow:

```ores
val Fnc<int, int> inc = (int x) -> x + 1;
val mapper = (String s) -> normalize(s);
```

Functions are ordinary typed values. `Fnc<A, B>` denotes a function from `A` to `B`; additional input parameters appear before the final return type parameter.

## 9. Conditionals

The canonical conditional form uses `if`, `do`, and `fi`:

```ores
if ready, authorized | process.is_admin; do
  return serve();
elseif retryable; do
  return retry();
else
  return reject();
fi
```

Within a condition:

- comma `,` means logical AND;
- single pipe `|` means logical OR;
- comma binds more tightly than pipe.

`&&` and `||` are not required syntax. The compiler may reserve them permanently.

## 10. Exceptions, `try`, and `defer`

Oreslang supports conventional structured exception handling:

```ores
try {
  risky();
} catch (err) {
  recover(err);
} finally {
  cleanup();
}
```

`defer` registers work for lexical-scope unwind and executes in LIFO order on normal return or exceptional exit:

```ores
fnc use_resource() => void {
  val r = open_resource();
  defer r.close();
  work(r);
  return;
}
```

`defer` does not replace `try/catch/finally`; both are supported.

## 11. Futures and `await`

`async` functions return `Future<T>`. `await` resolves a future and yields control to the scheduler rather than blocking an OS thread when the runtime can avoid it.

```ores
pub async fnc load() => Future<Response> {
  val response = await http.get("https://example.invalid");
  return response;
}
```

The exact scheduler is runtime-defined, but language semantics do not permit mutable state to become shared merely because multiple tasks exist.

## 12. Actors

Actors are the default concurrency isolation unit.

Core guarantees:

1. each actor owns its mutable heap;
2. mutable objects are never directly shared between actors;
3. communication occurs through typed messages;
4. send performs freeze/copy/serialization as needed;
5. deeply immutable values may use a zero-copy read-only representation;
6. a mutable alias cannot exist to a value that is shared read-only;
7. actor failure does not make another actor's heap directly reachable.

The initial runtime API is exposed through `process` rather than by granting access to the host OS process:

```ores
val worker = process.spawn_actor<Worker>();
await worker.send(Job{id: 10});
val result = await worker.receive<Result>();
```

Message types must satisfy the `Sendable` rules. Host objects, file descriptors, raw pointers, mutable foreign buffers, thread handles, and mutable closures are not sendable.

## 13. Read-only shared memory

Oreslang permits explicit sharing only for immutable data. A `Shared<T>` is deeply frozen and has no writable alias:

```ores
val Shared<Model> model = process.share_readonly(load_model());
```

Actors can access the same backing storage only through the read-only view. Creating `Shared<T>` consumes or freezes the source graph so that later mutation cannot race with readers.

## 14. Isolates

An isolate is stricter than an actor and is intended for tenant/FaaS security boundaries.

An isolate has:

- a separate language context/security domain;
- no implicit host object access;
- no native access;
- no arbitrary filesystem access;
- no child-process spawning;
- no shared mutable memory;
- capability-scoped network/stdio/environment access;
- bounded CPU/time/memory/mailbox policies supplied by the host platform;
- explicit serialization for cross-isolate messages.

Actors may live inside an isolate, but actor boundaries never weaken isolate policy. Cross-isolate values are copied/serialized unless the host explicitly exposes an immutable shared segment whose lifetime and permissions are enforced outside Oreslang.

## 15. Built-in globals

Two names are always reserved:

### `process`

A safe language runtime/process descriptor, not `java.lang.Process` and not unrestricted OS process access. It provides identity, actor/isolate metadata, scheduling, message operations, and explicitly granted capabilities.

### `stdio`

Capability-scoped input/output. In a strict FaaS isolate, stdin may be absent and output may be bounded or redirected by the host.

`print(value)` is shorthand for `stdio.print(value)`.

## 16. Reserved keywords

The v0 reserved set is:

`define`, `class`, `module`, `try`, `catch`, `finally`, `end`, `if`, `do`, `else`, `then`, `new`, `done`, `await`, `async`, `def`, `fnc`, `yield`, `super`, `elseif`, `switch`, `type`, `interface`, `impl`, `abstract`, `void`, `static`, `pub`, `private`, `return`, `defer`, `val`, `const`, `let`.

`def`, `then`, `done`, `yield`, `switch`, `impl`, `abstract`, and `static` are reserved even where the first interpreter does not yet execute every construct.

## 17. Truffle implementation pipeline

The reference implementation uses this pipeline:

1. UTF-8 source -> lexer;
2. lexer -> parser / syntax tree;
3. declaration collection across every module in the file;
4. generic resolution and structural static type checking;
5. actor/isolate sendability validation;
6. lowering to Truffle AST nodes;
7. execution on GraalVM/Truffle.

Parsing and type checking execute no user code. A source file is executable only after the static phases succeed.

## 18. FaaS host profile

For untrusted functions, the embedding host should create a restricted Graal polyglot context and provide only explicit capabilities. The host, not guest code, controls filesystem, networking, native access, environment variables, threads, and cross-language access.

The Oreslang runtime adds language-level actor isolation and sendability checks on top of that host boundary; it does not treat language semantics alone as a substitute for the host sandbox.

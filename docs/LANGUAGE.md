# Oreslang language design (v0.2)

Oreslang is a statically typed, structurally typed guest language for GraalVM/Truffle. Its core invariants are explicit mutation, actor-owned mutable heaps, message-only actor communication, read-only sharing, and a stricter isolate profile for untrusted FaaS execution.

## Files, modules, and imports

A source file may contain multiple named modules. A module is a namespace: exported members are accessed through the module name, such as `math.add(1, 2)`.

```ores
define module math
  pub fnc add(int a, int b) => int {
    return a + b;
  }
end

define module app
  pub fnc main() => void {
    val answer = math.add(40, 2);
    stdio.println(answer);
    return;
  }
end
```

Imports are explicit about what kind of symbol is entering the compilation unit:

```ores
import module foo from "../xyz";
import module {foo, bar} from "../xyz";
import class {x} from '../xyz';
import fnc * as funcs from '../xyz';
import * as x from './xyz';
```

Wildcard imports always require a namespace alias. This avoids silently injecting an unbounded set of names into the local scope. Import paths are part of the AST/compiler contract; filesystem/package resolution is a host build/bundling concern so strict isolates do not gain ambient filesystem access merely by using `import`.

## Module interfaces / OCaml-style module signatures

Interfaces can describe the structural public shape required of a module. A module opts into checking with `@AdheresTo(...)`:

```ores
define module contracts
  define interface MathApi
    fnc add(int a, int b) => int;
    String name;
  end
end

@AdheresTo(contracts.MathApi)
define module math
  pub fnc add(int a, int b) => int { return a + b; }
  pub val String name = "math";
end
```

Only exported (`pub`) module members satisfy an adherence contract. `@AdheresTo(A, B)` may name more than one interface.

## Functions and returns

Functions use `fnc` and are private by default. `pub` exports them. Return statements are always explicit; a non-`void` function must return on every control-flow path.

```ores
fnc add(int a, int b) => int {
  return a + b;
}

@Ret<int>
fnc answer() {
  return 42;
}
```

`@Ret<T>` and `=> T` are equivalent. If both are present they must agree. A function returns exactly one value; multiple logical values are represented by a tuple, array, object, class value, or another aggregate.

## Bindings

Every local binding is declared as exactly one of:

- `const`: compile-time constant; cannot be reassigned.
- `val`: runtime single-assignment binding; cannot be reassigned.
- `let`: mutable binding and the only local binding kind that may be reassigned.

```ores
const max = 10;
val request_id = process.context_id;
let retries = 0;
retries = retries + 1;
```

Destructuring carries mutability per element:

```ores
[const code, let body] = (200, "ok");
```

## Classes, receivers, multiple inheritance, and interfaces

Methods omit `fnc`. Instance methods always have an implicit receiver named `self`.

```ores
define class Box<T>
  val T value;

  @Ret<self>
  identity() {
    return self;
  }
end
```

The explicit receiver form remains available:

```ores
find(self Box)(int key) => self {
  return self;
}
```

The receiver variable name is always `self`.

A class may list multiple parent classes and multiple interfaces:

```ores
define class Combined extends Cacheable, Serializable implements HasId, Named
end
```

Parent order is significant and is the deterministic v0.2 method-resolution order after child methods: the first declared parent is searched before the next parent. The static checker rejects inheritance cycles and incompatible inherited member shapes. Child members may override inherited members only with compatible types.

`Object` and `List` are extensible base classes:

```ores
define class RecordBag extends Object
end

define class Names extends List
end
```

Inline object and array literals are values, not classes, and cannot be inherited from.

## Inline values

Structural inline object:

```ores
val user = obj{name: "Ada", age: 37};
stdio.println(user.name);
```

Inline array:

```ores
val values = arr[10, 20, 30];
val first = values[0];
```

`arr[...]` is the canonical inline-array spelling. The original bare `[...]` literal remains accepted for source compatibility and destructuring migration.

Tuples preserve per-position static types:

```ores
val pair = (1, "one");
[const number, let label] = pair;
```

## Structural typing and interfaces

Interfaces are structural contracts. Explicit `implements` asks the compiler to prove conformance and documents intent; structural compatibility does not require nominal ancestry in every context.

```ores
define interface Named
  String name;
end

define class User implements Named
  pub val String name;
end
```

Class interface satisfaction uses public members, including inherited public members.

## Option and null

Oreslang does **not** have ambient nullable references. A bare `null` value is a compile-time error, and `null` is not a standalone variable/parameter/return type.

Optionality is explicit:

```ores
fnc lookup(String key) => Option<Value> {
  // explicit Option value construction API evolves separately
}
```

`Option<null>` is accepted only as an explicit type-level escape hatch when an interoperability boundary truly needs to preserve a null marker. The `null` marker cannot escape that direct `Option<null>` position. `Option<void>` is invalid; use `void` when a function returns no value.

## Numbers

Built-in numeric families include integral, floating, decimal, and complex types. Imaginary literals use `i`:

```ores
const complex z = 3 + 4i;
```

Numeric widening is loss-aware; real values can widen toward complex values, but silent lossy narrowing is not performed.

## Lambdas

Lambdas use `->`:

```ores
val Fnc<int, int> inc = (int x) -> x + 1;
```

## Conditionals

`fi` is a real, distinct conditional terminator. It is not an alias for module/class `end`.

```ores
if ready, authorized | process.is_admin; do
  return serve();
elseif retryable; do
  return retry();
else
  return reject();
fi
```

Within a condition, comma means AND and `|` means OR. Comma binds more tightly.

## Exceptions and defer

Both structured exceptions and lexical `defer` are supported:

```ores
try {
  risky();
} catch (err) {
  recover(err);
} finally {
  cleanup();
}
```

`defer` executes in LIFO order when its lexical scope unwinds, including returns and exceptional exits.

## Async / await

`async` and `await` are reserved and parsed. `await` unwraps future-like runtime values. The scheduler is intentionally separate from the language surface so actor isolation does not depend on a specific OS-thread implementation.

## Actors

Actors own their mutable heaps. Cross-actor communication occurs through mailboxes, and message values are frozen/copied/serialized at the runtime boundary. Arbitrary mutable host objects are rejected as messages. Deeply immutable values may use read-only sharing.

## Isolates

An isolate is stricter than an actor and is intended as a FaaS/tenant security boundary. Strict isolate contexts deny host reflection, native access, arbitrary filesystem/IO, child-process creation, guest-created threads, environment access, and unrestricted polyglot access unless an explicit capability is granted by the host.

Actors may run inside an isolate. Actor semantics never weaken isolate policy.

## Built-in globals

`process` is an Oreslang runtime descriptor/capability facade, not unrestricted OS process access. `stdio` is capability-scoped standard IO. `print(value)` is shorthand for the output facade.

## Compiler pipeline

1. UTF-8 source -> lexer.
2. lexer -> parser / AST.
3. imports and declarations are collected without executing user code.
4. generic, structural, module-interface, inheritance, mutability, and return-flow checks run.
5. actor/isolate sendability constraints are enforced at relevant runtime boundaries.
6. checked source is lowered/executed as Truffle guest code.

The parser and static checker execute no user code.

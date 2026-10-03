# Mixed Java / Oreslang source files

Oreslang source identity is **filesystem-path based**. Do not add Java/Go-style source declarations such as:

```ores
module demo;
```

A file's canonical Unix-style path is its code-unit identity and the base for relative dependency lookup.

## Java inside an `.ores` file

An `.ores` file is Oreslang by default. There are two deliberately different
inline-Java forms:

- `java { ... }` **declares Java**. It contributes a Java type and does not
  represent an Ores runtime statement.
- `do java { ... }` **runs Java statements** at that exact Ores control-flow
  point.

For declarations, use a `java { ... }` language island:

```ores
pub fnc decorate(String value) => String {
  return JavaHelper.decorate(value);
}

java {
  final class JavaHelper {
    public static String decorate(String value) {
      return "[" + value + "]";
    }
  }
}
```

Each `java { ... }` island currently declares exactly one top-level Java class, interface, record, or enum. It must not contain a Java `package` declaration. The compiler assigns an internal package derived from the canonical source path and exposes the Java type to the Oreslang portion of the same source unit.

The declaration itself is not an Ores statement and is removed before the Ores
parser runs. Java constructors, methods, and static initialization retain normal
Java semantics when that declared type is later loaded/used.

## Executing Java with `do java`

Use `do java { ... }` when the Java body should execute immediately at an Ores
statement position:

```ores
pub fnc signal() => void {
  stdio.println("called from Java");
  return;
}

pub fnc main() => void {
  do java {
    Ores.signal();
  }

  return;
}
```

The compiler lowers each execution island to a generated package-local Java
helper with a `public static void run()` method and rewrites the Ores statement
to a normal call to that generated helper. This means the Java executes only
when Ores control flow reaches the statement.

The initial contract is intentionally narrow and safe:

- `do java { ... }` is **statement-only** and has Ores type `void`;
- a value-returning Java `return expr;` is rejected by javac;
- the block does **not** implicitly capture Ores locals, actor state, borrows, or
  closure environments;
- Java `return;` returns only from the generated Java helper, not from the
  enclosing Ores `fnc`/`routine`;
- Java exceptions cross back through the ordinary Java/Ores call boundary;
- an optional trailing semicolon after the Java block is accepted; and
- the generated helper is an implementation detail and cannot be named in
  source.

If Java needs Ores values as inputs or needs to return a value, keep that
boundary typed and explicit. Prefer a normal imported Java call or declare a
Java helper with parameters:

```ores
java {
  final class JavaMath {
    public static int twice(int value) {
      return value * 2;
    }
  }
}

pub fnc twice(int value) => int {
  return JavaMath.twice(value);
}
```

That path gives Ores an ordinary call boundary to type-check instead of creating
an implicit capture channel.


The Java island may call exported Oreslang functions through the generated `Ores` facade in the same generated package:

```ores
pub fnc twice(int value) => int {
  return value * 2;
}

java {
  final class JavaHelper {
    public static int six() {
      return Ores.twice(3);
    }
  }
}
```

## Oreslang inside a `.java` file

A `.java` file is Java by default. Use an `ores { ... }` island:

```java
import java.util.ArrayList;

public final class MixedDemo {
  public static void main(String[] args) {
    var values = new ArrayList<String>();
    values.add("java");

    Object same = Ores.identity(values);
    if (same != values) throw new AssertionError("identity changed");

    System.out.println(Ores.count(values));
  }

  ores {
    import class ArrayList as JArrayList from "java:java.util.ArrayList";

    pub fnc identity(JArrayList value) => JArrayList {
      return value;
    }

    pub fnc count(JArrayList value) => int {
      return value.size();
    }
  }
}
```

The compiler generates a package-local `Ores` facade for public Oreslang functions. Primitive return types are converted to their Java equivalents; imported Java return types remain the Java class. `Ores.call("function_name", args...)` is always available as the dynamic escape hatch.

## Object passing

Inside the same trusted JVM/runtime domain, Java objects cross the Java/Ores boundary **by reference**. They are not serialized into Oreslang collections and reconstructed later. Returning the same Java object from Oreslang therefore preserves Java reference identity.

Java host access is still capability checked. Imported Java classes require `JAVA_INTEROP` plus the exact host-class allowlist. Arbitrary Java source islands additionally require `JAVA_SOURCE_INTEROP`.

Raw Java object references never become a mechanism for crossing an adversarial/untrusted isolate boundary. Private/adversarial isolation must use explicit messages, immutable copies, safe shared buffers, or capability handles instead.

## Security and execution mode

`java { ... }`, `do java { ... }`, and `ores { ... }` source islands are intentionally more privileged than a `java:` class import because arbitrary Java source executes with ordinary JVM authority.

For that reason:

- `JAVA_SOURCE_INTEROP` requires `JAVA_INTEROP`; both are required.
- adversarial policies cannot acquire `JAVA_SOURCE_INTEROP`;
- private actors have Java-source and Java-host authority stripped;
- annotation processing is disabled while compiling source islands;
- source-island compilation currently requires `--mode=jit`;
- AOT/hybrid deployments must precompile the Java side instead of compiling arbitrary Java source at runtime.

Treat `JAVA_SOURCE_INTEROP` as trusted-host-code authority, not as a granular
sandbox capability. Inline Java is compiled into the host JVM class-loader
domain and can use ordinary Java APIs available on that compilation classpath.
The Ores capability allowlist still protects normal `java:` imports, but it
cannot make arbitrary inline Java safe for hostile tenants after granting
`JAVA_SOURCE_INTEROP`.

## Path lookup

Relative Oreslang imports remain Unix-style filesystem lookups:

```ores
import fnc hash from "./crypto/hash.ores";
import class User from "../models/user.ores";
```

Mixed source units may use an explicit `.java` path where that Java file contains an `ores { ... }` island. Extensionless resolution checks `.ores` first, then `.java`.

There is no synthesized Oreslang module name. Canonical paths remain the linker/incremental-compiler identity.

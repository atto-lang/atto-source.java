# Copy-policy compiler conformance (spec-only)

This directory is **not** registered with the runnable
`contracts/v1/semantics` suite. Every entry in `cases.json` is
`pending` for every backend; the examples below intentionally use
**future syntax**. A documentation-only change does not pass these cases.

## Representative future cases

### Prohibited field copy; permitted borrowed access

```oreslang
define class Credentials as
  @NoCopy
  pub val int key = 42;
end

fnc wrong(Credentials c): void {
  val int second = rt copy c.key; // E_COPY_FORBIDDEN
  return;
}

fnc allowed(Credentials c): void {
  val view = rt borrow c.key;
  return;
}
```

The annotation must survive type checking and public import/linking.
An ordinary class is already move-only; the field restriction is also
important when a copy-capable aggregate or projection is introduced.

### Instance sealing — consuming, not a runtime flag

```oreslang
@Copyable
define class Snapshot as
  pub val int version = 7;
end

fnc example(): void {
  val NoCopy<Snapshot> protected = rt nocopy new Snapshot();
  val NoCopy<Snapshot> moved = rt take protected; // legal ownership transfer
  val copied = rt copy moved;                      // E_COPY_INSTANCE
  return;
}
```

A `NoCopy<T>` value cannot be silently cast back to a copy-capable
`T`, even when routed through a generic, collection, FFI handle, or
async closure. `@Copyable` remains rejected unless a verified copy
contract for the type is available.

### Singleton is an anchored identity

```oreslang
define singleton Registry as
  pub val int count = 0;
end

fnc example(): void {
  val view = rt borrow Registry; // permitted in current scope
  val second = rt copy Registry; // E_SINGLETON_COPY
  val taken = rt take Registry;  // E_SINGLETON_IDENTITY
  val made = new Registry();     // E_SINGLETON_IDENTITY
  return;
}
```

A single declaration yields one logical identity per declared runtime
scope; tenant and isolate boundaries must never silently share it.

### Legal, but discouraged

```oreslang
define class Config as
  @WarnOnCopy
  pub val int version = 1;
end

fnc example(Config c): void {
  val int version = rt copy c.version; // W_COPY_DISCOURAGED
  return;
}
```

`-Werror=copy` promotes the diagnostic; it never downgrades
a hard no-copy error into a warning.

## Executable gate to implement

1. Adapt the compiler parser/AST for class annotations, singleton
   declarations, and `NoCopy<T>`/`rt nocopy` with unknown operations
   rejected before lowering.
2. Implement static place, field, and provenance checker. Report stable
   error/warning code and source position, with no runtime fallback.
3. Turn each `cases.json` ID into a source fixture and expected
   diagnostic/exit test. For warning tests assert warning code and
   successful compilation, plus `-Werror=copy` rejection.
4. Run against each backend's exact head. Only then change that backend
   from `pending` to `required`; record head/tree SHA and nonzero
   executed CI steps. Do not use source-free static lint checks as proof
   of Oreslang guest compiler conformance.

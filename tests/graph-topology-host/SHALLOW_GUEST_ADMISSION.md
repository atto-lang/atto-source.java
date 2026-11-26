# Shallow-copy guest admission gate — 2026-11-25

Status: NOT_EXECUTED guest; host substrate only. Parent #1880 evaluator
blob e731872ce017a3a311eb371c6a9587e24f6fe948. Do not remove
E_COPY_SHALLOW_ALIAS for nested mutable fields until all mutation entry
points and scope/ownership transitions are protected.

## Audit of current evaluator

- OresEvalRootNode.java:5996 computed class-field assignment writes the map.
- :6059 member assignment writes the map.
- :8288 assignment expression/constructor initialization writes the map.
- :6015 indexed list store; :9861 List.add; :9872 List.set mutate List.
- :12490 TypedGuestSequence extends ArrayList; inherited mutators, iterator
  removal, subList, bulk operations, and host exposure must not evade authority.
- :12505 OresObject exposes fields.values() through sharedStateChildren;
  inspection/FFI/actor/mutex paths must not introduce a writable alias.

These are identified seams, not an exhaustive proof. Do not use an
evaluator-wide identity tag or name-based declaration as owner evidence.
Start with a root-local, nonescaping, fixed nominal class profile;
lower every root-slot assignment, nested access and block cleanup through
one checked domain. Imported, actor, task, closure, FFI and opaque host
paths must reject unless separately proved. Preserve full type checks,
val/let rules, source evaluation order, constructor initialization and
exclusive read/write loan rights.

## Target guest source (NOT admitted yet)

Positive expected output 42:

```atto
define class Scores as
    pub let int n = 20;
end
define class Person as
    pub let Scores scores = new Scores();
    pub let int age = 1;
end
pub fnc main(): int {
    let Person a = new Person();
    block {
        let Person b = copy! a;
        b.age = 2;
        b.scores = new Scores();
        b.scores.n = 22;
        a.scores.n = 20;
        return a.scores.n + b.scores.n;
    }
}
```

Check source syntax against the actual compiler edition before accepting
any receipt. Required negative companion: replace the entire replacement
statement with `b.scores.n = 22;` while still shared; require a specific
read-only diagnostic, not any parse error. Mirror on a, including a
preexisting alias. Positive companion: replace original slot instead;
preserve independent values. Additional cases: block exit restores rights,
val slot denial, third copy retains protection, live read loan blocks
thaw, exclusive loan blocks copy and detachment, nested containing object
denial, method/iterator mutation, foreign generation, failed donor
replacement preserves old value and donor. Record/map/array/list lowering
are separate admission gates. Negative tests must not pass solely because
the compiler rejects the copy expression itself.

No guest status changes to PASS from host test runs.

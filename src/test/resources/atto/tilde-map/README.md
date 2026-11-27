# Nonempty typed Map.of: staged guest conformance

These fixtures exercise the canonical `Map.of<K,V>(key => value, ...)` factory,
with directly typed contextual `Map.of(key => value, ...)` cases.
Java #2735 must be integrated with the constructor-removal branch before the
positive fixtures can establish compiled guest behavior.

- `Map.of<K,V>(...)` constructs an OresKeyedMap, not an unchecked Java host map.
- Static type checking must validate key/value types and ownership, and reject
  untyped `Map.of(...)` where no supported Map<K,V> witness is available.
- Duplicate logical String/Symbol keys must reject the whole staged Map
  transaction, not publish a partially initialized value.
- Evaluation order is source order. External effects of argument evaluation
  are not rolled back on transactional construction failure.
- Map spreads and mixed record-style entries must reject; unsupported value
  shapes must fail at compilation, not fall back to reflection.
- All removed `~map(...)`, contextual `~(... => ...)`, and typed tilde
  spellings reject at parse time. They are not positive guest fixtures.
- The .atto programs, expected stdout, and tests are **AUTHORED / NOT_EXECUTED**.
  Exact-head JDK25/JUnit/Truffle execution and Native Image proof remain pending.

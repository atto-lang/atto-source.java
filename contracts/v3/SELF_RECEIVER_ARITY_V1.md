# Receiver declaration and call-site argument provenance v1

This contract documents the implemented **Java source parser** and existing
method overload identity, plus backend/actor audit gates; it is **not** proof
of native LLVM actor/class method execution.

| Declaration | Effective method argument slots |
| --- | --- |
| `foo(int x)` | 1 |
| `foo(self, int x)` | 1 |
| `foo(mut self, int x)` | 1 |
| `foo(self Foo)(int x)` | 1 |
| `foo(self)` | 0 |
| `foo(self, self peer, int x)` | 2 |
| `static fnc foo(int x)` | 1 |

Parsing:
- Only a FIRST standalone `self` or `mut self`, terminated by comma or
  closing parenthesis, is a receiver binder.
- Existing `foo(self Foo)(args)` and `foo(mut self)(args)` remain accepted.
- `self peer` is a typed VALUE formal, not the receiver.
- The binder is stored exclusively in `MethodDecl.explicitReceiverType`
  (or left implicit), NEVER inserted into `MethodDecl.parameters`.
- Reject `foo(self,)`, static `foo(self,...)`, later bare `self`, malformed
  receiver combinations, and conflicts with implicit receiver declarations.
- Actor method declarations share the same identity; the actor runtime
  always owns its instance receiver.

Calls:
- `receiver.foo(self, 5)` has *two* supplied expressions, regardless of
  spelling of receiver at declaration. Receiver to left of dot is
  independent; self in call parentheses is ALWAYS an ordinary value.
- The declaration overload is selected by supplied VALUE arity, adjusted
  only by **checked omission/default slots** under the separate, versioned
  parameter presence policy. A receiver is never counted as an omittable
  argument or given a default, nor used in named binding.
- Once selected, check types and ownership of the actuals without
  reinterpreting a supplied self as a receiver; in particular actor-owned
  instance self cannot be serialized/copied into a mailbox.

Parity gates:
1. Parser/AST same-arity conflicts and positive inline/legacy forms.
2. Inherited, interface and defaulted arity collisions; explicit receiver
   mutability and async/generic method type proof.
3. Runtime class member call correct signature and source-effect order.
4. Actor cross-boundary negative paths (including aliases and nested
   captures), stop/supervision and multi-carrier state safety.
5. Exact-source JDK25 Maven/JUnit + Graal Native Image, LLVM when feature
   is supported, with actual runner steps and reproducible commit SHA.

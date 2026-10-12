# ActorUtils.pipe and static namespace selection

`ActorUtils.pipe(spawn A(), spawn B(), spawn C())` and
`ActorUtils::pipe(spawn A(), spawn B(), spawn C())` perform the same nominal
port checks as `spawn A() pipe spawn B() pipe spawn C()`. The intrinsic takes
at least two stages and infers port types. It does not accept explicit type
arguments. Actors must declare `StreamOperator<I,O>`; adjacent ports must match.
The existing Java ActorStream runtime rejection remains after validation:
these forms do not yet execute a guest actor graph.

Class static functions also accept `MathOps.twice(3)` and `MathOps::twice(3)`.
Dot follows ordinary value lookup. Double colon selects the declared namespace,
so a local variable named `MathOps` does not hide the class. Visibility and
static function arity checks still apply. Qualified paths such as
`api.MathOps::twice(3)` are supported; arbitrary value expressions before
`::` are rejected. Prefer `::` when a value name shadows the namespace.

Tests cover a fifteen-stage topology through both ActorUtils spellings,
invalid arity, a mismatch in the final edge, real static execution with a
shadowed name, and rejection of an instance method through a class namespace.

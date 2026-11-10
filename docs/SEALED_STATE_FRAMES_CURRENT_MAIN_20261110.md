# Sealed state and execution-frame integration

This candidate reconciles the state-loan stack through #2747 with main
`6d8eb70048eafc9a84c5aebe9c1bb06104f16e76` and the actor-frame integration
through #2781 (`66e41e813d11305b63285f5d6102cc5dd6ee7c44`). All histories are
preserved. It remains a draft, not backend parity or independent heap proof.

## Resulting source and authority

New source follows the language spec's `critical-read` and `critical-write`:

```attolang
define actor Counter as
  state { revision: int = 0; }
  receive(ActorMail<String> mail): void {
    self.handle_state(critical-write |s| -> { s.revision = 1; });
    self.handle_state(critical-read |s| -> { val revision = s.revision; });
    self.end();
    return;
  }
end
```

The parser emits a closed internal mode enum, never a guest-forgeable string.
Bracket modes and the one-argument write form remain migration spellings.
The typed sealed schema now reaches its existing checker/evaluator path instead
of the superseded unconditional main parser rejection. Unimplemented isolated
actor/state-container/pipe and actor factory gates stay closed.

The ownership pass understands both explicit mode and legacy callbacks. The
intrinsic's immediate synchronous body is checked as a lexical scope: outer
captures are not spuriously moved into an escaping closure, but actual moves in
the body still consume the outer owner. Read mode gets an immutable borrow;
suspension, nested closure/borrow escape, forged modes and direct state access
remain rejected. Ordinary general-purpose closure capture semantics are unchanged.

A runtime permit is bound to the exact live ActorCell, physical segment and
current logical execution. Its state-loan view retains the invocation/task's
ExecutionId, ancestry and actor frame allocation domain. It no longer invents a
new execution identity per critical callback. Synchronous calls share the
existing execution stack; the view does not create a new stack. Runtime host
contexts and supervisors cannot mint guest state permits or access child state.

## Reader/writer lifetime

The region arbiter admits compatible readers or one writer, with a FIFO writer
barrier. Separate execution/handle/slot fences prevent duplicate physical
holders. A queued acquisition's identity stays reserved until both its loan and
Future notification callbacks physically retire. Revoker failures quarantine
reclamation. STOP revokes logical access before the last holder unwinds and
must not close the actor arena or publish DONE prematurely.

The carrier stress fixture now waits for the runtime notification receipt before
reusing an execution after `get()`/loan release. The STOP fixture issues joining
host STOP on a separate host thread while its deliberate test loan is held.
Neither change removes runtime retirement fences or asserts guest concurrency.

## Executed evidence and limits

Local Temurin 25/macOS arm64 execution passes **306 tests**, with zero failures,
errors or skips: 89 sealed-state/schema/source/runtime tests, 110 frame and
boundary tests, and 107 existing mutex tests. This includes actual compiler and
Truffle guest state write/read, not only host loan modeling. Host carrier tests
exercise 3/7/15 carriers. Nine local conformance fixtures are migrated to `.atto`,
canonical critical modes and `rt mut borrow`, with new exact Git blob checks.
They are local ports of common-source #187, not unchanged original corpus bytes
or verified equivalent LLVM/.NET execution.

Production source actors here still admit one physical turn at a time. Host RW
concurrency does not establish concurrent-default guest actors. Synchronous
source critical acquisition rejects queuing; scheduler-owned suspension/resume
admission is still required for concurrent guest callers. Mutable composite
read views remain fail-closed. General arena allocation/checked ref32 lowering,
independent guest heaps, complete continuation frames, singleton/symbol shared
regions and LLVM/.NET compiler conformance remain open. No Native Image or
source-exact hosted CI result is claimed by local Maven execution.

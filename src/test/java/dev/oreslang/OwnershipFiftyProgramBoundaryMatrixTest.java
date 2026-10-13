package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fifty distinct executable compiler-admission programs, not 50 variations of
 * the same assertion. Every source must parse before the ownership pass runs;
 * expected rejections are matched to the OWNERSHIP diagnostic so a parser
 * failure cannot be mistaken for proof of borrow safety.
 *
 * Coverage: five callable/actor kinds; await, cooperate, blocking channel and
 * select operations; sync/async generator yields; deferred nonblocking channel
 * registration; lexical release; rt copy/share/take.
 *
 * This is static admission evidence, NOT a proof that runtime actor scheduling
 * is data-race-free, nor an LLVM execution/parity certificate.
 */
final class OwnershipFiftyProgramBoundaryMatrixTest {
    private record ProgramCase(String name, String source, boolean accepted, String diagnostic) {}
    private static final List<ProgramCase> PROGRAMS = List.of(
        new ProgramCase("reject fnc cooperate with shared loan", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nrt cooperate;\nstdio.println(view.value);\nreturn;\n}", false, "cannot cooperate"),
        new ProgramCase("reject fnc await with shared loan", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = await (nb readch ch);\nstdio.println(view.value);\nreturn;\n}", false, "cannot await"),
        new ProgramCase("reject fnc readch with shared loan", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = readch ch;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject fnc writech with shared loan", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nwritech ch, 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject fnc select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nselect { case readch ch: val item { stdio.println(item); } }\nstdio.println(view.value);\nreturn;\n}", false, "cannot select"),
        new ProgramCase("reject fnc dynamic-select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval Array<SelectCase> cases = [SelectCase.read(ch)]; val Option<SelectResult> result = select from cases;\nstdio.println(view.value);\nreturn;\n}", false, "cannot select from cases"),
        new ProgramCase("reject async fnc cooperate with shared loan", "define class Box as\n  pub let int value = 7;\nend\nasync fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nrt cooperate;\nstdio.println(view.value);\nreturn;\n}", false, "cannot cooperate"),
        new ProgramCase("reject async fnc await with shared loan", "define class Box as\n  pub let int value = 7;\nend\nasync fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = await (nb readch ch);\nstdio.println(view.value);\nreturn;\n}", false, "cannot await"),
        new ProgramCase("reject async fnc readch with shared loan", "define class Box as\n  pub let int value = 7;\nend\nasync fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = readch ch;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject async fnc writech with shared loan", "define class Box as\n  pub let int value = 7;\nend\nasync fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nwritech ch, 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject async fnc select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nasync fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nselect { case readch ch: val item { stdio.println(item); } }\nstdio.println(view.value);\nreturn;\n}", false, "cannot select"),
        new ProgramCase("reject async fnc dynamic-select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nasync fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval Array<SelectCase> cases = [SelectCase.read(ch)]; val Option<SelectResult> result = select from cases;\nstdio.println(view.value);\nreturn;\n}", false, "cannot select from cases"),
        new ProgramCase("reject suspends fnc cooperate with shared loan", "define class Box as\n  pub let int value = 7;\nend\nsuspends fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nrt cooperate;\nstdio.println(view.value);\nreturn;\n}", false, "cannot cooperate"),
        new ProgramCase("reject suspends fnc await with shared loan", "define class Box as\n  pub let int value = 7;\nend\nsuspends fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = await (nb readch ch);\nstdio.println(view.value);\nreturn;\n}", false, "cannot await"),
        new ProgramCase("reject suspends fnc readch with shared loan", "define class Box as\n  pub let int value = 7;\nend\nsuspends fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = readch ch;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject suspends fnc writech with shared loan", "define class Box as\n  pub let int value = 7;\nend\nsuspends fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nwritech ch, 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject suspends fnc select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nsuspends fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nselect { case readch ch: val item { stdio.println(item); } }\nstdio.println(view.value);\nreturn;\n}", false, "cannot select"),
        new ProgramCase("reject suspends fnc dynamic-select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nsuspends fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval Array<SelectCase> cases = [SelectCase.read(ch)]; val Option<SelectResult> result = select from cases;\nstdio.println(view.value);\nreturn;\n}", false, "cannot select from cases"),
        new ProgramCase("reject actor fnc cooperate with shared loan", "define class Box as\n  pub let int value = 7;\nend\nactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nrt cooperate;\nstdio.println(view.value);\nreturn;\n}", false, "cannot cooperate"),
        new ProgramCase("reject actor fnc await with shared loan", "define class Box as\n  pub let int value = 7;\nend\nactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = await (nb readch ch);\nstdio.println(view.value);\nreturn;\n}", false, "cannot await"),
        new ProgramCase("reject actor fnc readch with shared loan", "define class Box as\n  pub let int value = 7;\nend\nactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = readch ch;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject actor fnc writech with shared loan", "define class Box as\n  pub let int value = 7;\nend\nactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nwritech ch, 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject actor fnc select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nselect { case readch ch: val item { stdio.println(item); } }\nstdio.println(view.value);\nreturn;\n}", false, "cannot select"),
        new ProgramCase("reject actor fnc dynamic-select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval Array<SelectCase> cases = [SelectCase.read(ch)]; val Option<SelectResult> result = select from cases;\nstdio.println(view.value);\nreturn;\n}", false, "cannot select from cases"),
        new ProgramCase("reject isoactor fnc cooperate with shared loan", "define class Box as\n  pub let int value = 7;\nend\nisoactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nrt cooperate;\nstdio.println(view.value);\nreturn;\n}", false, "cannot cooperate"),
        new ProgramCase("reject isoactor fnc await with shared loan", "define class Box as\n  pub let int value = 7;\nend\nisoactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = await (nb readch ch);\nstdio.println(view.value);\nreturn;\n}", false, "cannot await"),
        new ProgramCase("reject isoactor fnc readch with shared loan", "define class Box as\n  pub let int value = 7;\nend\nisoactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval int item = readch ch;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject isoactor fnc writech with shared loan", "define class Box as\n  pub let int value = 7;\nend\nisoactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nwritech ch, 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot readch/writech"),
        new ProgramCase("reject isoactor fnc select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nisoactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nselect { case readch ch: val item { stdio.println(item); } }\nstdio.println(view.value);\nreturn;\n}", false, "cannot select"),
        new ProgramCase("reject isoactor fnc dynamic-select with shared loan", "define class Box as\n  pub let int value = 7;\nend\nisoactor fnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval Array<SelectCase> cases = [SelectCase.read(ch)]; val Option<SelectResult> result = select from cases;\nstdio.println(view.value);\nreturn;\n}", false, "cannot select from cases"),
        new ProgramCase("reject generator fnc borrowed yield result", "define class Box as\n  pub let int value = 7;\nend\ngenerator fnc probe(): int {\nlet Box target = new Box();\nyield rt borrow target;\nreturn;\n}", false, "cannot yield a borrowed reference"),
        new ProgramCase("reject generator fnc shared loan before yield", "define class Box as\n  pub let int value = 7;\nend\ngenerator fnc probe(): int {\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nyield 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot yield"),
        new ProgramCase("reject generator fnc mutable loan before yield", "define class Box as\n  pub let int value = 7;\nend\ngenerator fnc probe(): int {\nlet mut Box target = new Box();\nval borrow mut Box view = rt borrow mut target;\nyield 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot yield"),
        new ProgramCase("reject generator fnc scoped nested loan at yield", "define class Box as\n  pub let int value = 7;\nend\ngenerator fnc probe(): int {\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; yield 1; stdio.println(view.value); }\nreturn;\n}", false, "cannot yield"),
        new ProgramCase("reject async generator fnc borrowed yield result", "define class Box as\n  pub let int value = 7;\nend\nasync generator fnc probe(): int {\nlet Box target = new Box();\nyield rt borrow target;\nreturn;\n}", false, "cannot yield a borrowed reference"),
        new ProgramCase("reject async generator fnc shared loan before yield", "define class Box as\n  pub let int value = 7;\nend\nasync generator fnc probe(): int {\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nyield 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot yield"),
        new ProgramCase("reject async generator fnc mutable loan before yield", "define class Box as\n  pub let int value = 7;\nend\nasync generator fnc probe(): int {\nlet mut Box target = new Box();\nval borrow mut Box view = rt borrow mut target;\nyield 1;\nstdio.println(view.value);\nreturn;\n}", false, "cannot yield"),
        new ProgramCase("reject async generator fnc scoped nested loan at yield", "define class Box as\n  pub let int value = 7;\nend\nasync generator fnc probe(): int {\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; yield 1; stdio.println(view.value); }\nreturn;\n}", false, "cannot yield"),
        new ProgramCase("accept cooperate after scope closes", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; stdio.println(view.value); }\nrt cooperate;\nreturn;\n}", true, null),
        new ProgramCase("accept await after scope closes", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; stdio.println(view.value); }\nval int item = await (nb readch ch);\nreturn;\n}", true, null),
        new ProgramCase("accept readch after scope closes", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; stdio.println(view.value); }\nval int item = readch ch;\nreturn;\n}", true, null),
        new ProgramCase("accept writech after scope closes", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; stdio.println(view.value); }\nwritech ch, 1;\nreturn;\n}", true, null),
        new ProgramCase("accept select after scope closes", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nblock { val borrow Box view = rt borrow target; stdio.println(view.value); }\nselect { case readch ch: val item { stdio.println(item); } }\nreturn;\n}", true, null),
        new ProgramCase("accept nonblocking readch with live shared borrow", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nval Future<int> pending = nb readch ch;\nstdio.println(view.value);\nreturn;\n}", true, null),
        new ProgramCase("accept nonblocking select not capturing borrowed value", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nnb select { case readch ch: val item { stdio.println(item); } }\nstdio.println(view.value);\nreturn;\n}", true, null),
        new ProgramCase("accept select with default cannot suspend", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void {\nval Channel<int> ch = Channel.new<int>(1);\nlet Box target = new Box();\nval borrow Box view = rt borrow target;\nselect { case readch ch: val item { stdio.println(item); } default: { stdio.println(1); } }\nstdio.println(view.value);\nreturn;\n}", true, null),
        new ProgramCase("reject copy of borrowed class", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void { val Box target = new Box(); val borrow Box view = rt borrow target; val candidate = rt copy view; return; }", false, "rt copy"),
        new ProgramCase("reject share of borrowed class", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void { val Box target = new Box(); val borrow Box view = rt borrow target; val candidate = rt share view; return; }", false, "rt share"),
        new ProgramCase("reject taking borrowed class", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void { val Box target = new Box(); val borrow Box view = rt borrow target; val candidate = rt take view; return; }", false, "rt take"),
        new ProgramCase("accept taking unloaned owner", "define class Box as\n  pub let int value = 7;\nend\nfnc probe(): void { val Box target = new Box(); val candidate = rt take target; stdio.println(candidate.value); return; }", true, null)
    );

    @TestFactory
    Stream<DynamicTest> fiftyDistinctCompilerAdmissionPrograms() {
        assertEquals(50, PROGRAMS.size(), "The matrix must contain fifty programs");
        assertEquals(50, PROGRAMS.stream().map(ProgramCase::source).distinct().count(),
                "No duplicate source programs may inflate coverage");
        return PROGRAMS.stream().map(program -> DynamicTest.dynamicTest(program.name(), () -> {
            Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(program.source()),
                    "Invalid fixture grammar in " + program.name());
            if (program.accepted()) {
                assertDoesNotThrow(() -> OwnershipChecker.check(parsed), program.name());
            } else {
                IllegalArgumentException diagnostic = assertThrows(IllegalArgumentException.class,
                        () -> OwnershipChecker.check(parsed), program.name());
                assertTrue(diagnostic.getMessage().contains(program.diagnostic()),
                        () -> program.name() + ": expected " + program.diagnostic()
                                + " but got " + diagnostic.getMessage());
            }
        }));
    }
}

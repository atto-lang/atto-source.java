package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ProceduralDoMatchTest {

    @Test
    void doMatchBracesCarryExplicitProceduralAstFlag() {
        Ast.Program program=Parser.parse("""
                fnc f(Option<int> input): int {
                  do match input over {
                    on Some(n) {
                      return n;
                    }
                    on None {
                      return 0;
                    }
                  }
                }
                """);
        Ast.FunctionDecl f=(Ast.FunctionDecl)program.modules().getFirst().declarations().getFirst();
        Ast.MatchStmt statement=(Ast.MatchStmt)f.body().getFirst();
        assertTrue(statement.procedural());
        assertTrue(statement.ordered());
        assertEquals(2,statement.arms().size());
        assertInstanceOf(Ast.ReturnStmt.class,statement.arms().getFirst().body().getFirst());

        Ast.FunctionDecl plain=(Ast.FunctionDecl)Parser.parse("""
                fnc g(Option<int> input): int {
                  match input over
                    on Some(n) -> { return n; }
                    on None -> { return 0; }
                  end
                }
                """).modules().getFirst().declarations().getFirst();
        assertFalse(((Ast.MatchStmt)plain.body().getFirst()).procedural());
    }

    @Test
    void doMatchSupportsOverEndAndOptionalArrowInsideBraces() {
        for(String body:List.of(
                "do match value over on Some(n) -> { return n; } on None -> { return 0; } end",
                "do match value over { on Some(n) -> { return n; } on None -> { return 0; } }",
                "do match value over { on Some(n) { return n; } on None { return 0; } }")) {
            var source="fnc f(Option<int> value): int { "+body+" }";
            var typed=TypeChecker.check(Parser.parse(source));
            assertDoesNotThrow(()->OwnershipChecker.check(typed));
        }
    }

    @Test
    void armReturnExitsTheEnclosingCallableAndExecutionRemainsOrdered() throws Exception {
        String source="""
                fnc compute(Option<int> value): int {
                  do match value over {
                    on Some(n) {
                      return n + 1;
                    }
                    on None {
                      return 0;
                    }
                  }
                }
                pub fnc main(): void {
                  stdio.println(compute(Some(41)));
                  stdio.println(compute(None));
                  return;
                }
                """;
        assertDoesNotThrow(()->OwnershipChecker.check(TypeChecker.check(Parser.parse(source))));
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        var guest=Source.newBuilder(OresLanguage.ID,source,"procedural-do-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try(Context ctx=Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()){
            ctx.eval(guest);
        }
        assertEquals("42\n0",output.toString(StandardCharsets.UTF_8)
                .trim().replace("\r",""));
    }

    @Test
    void variantDestructuringIsPreservedButNeverMiscompiledAsOption() {
        var source="""
                define actor WorkerActor as
                  @Implements
                  receive(Message m) {
                    do match m over {
                      on Message.Compute as (text, n) {
                        self.respond(Message.Result(text, n + 1));
                        self.done();
                      }
                    }
                  }
                end
                """;
        Ast.Program ast=assertDoesNotThrow(()->Parser.parse(source));
        Ast.ClassDecl actor=(Ast.ClassDecl)ast.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl receive=actor.methods().stream()
                .filter(m->m.name().equals("receive")).findFirst().orElseThrow();
        Ast.MatchStmt match=(Ast.MatchStmt)receive.body().getFirst();
        assertTrue(match.procedural());
        assertInstanceOf(Ast.ConstructorPattern.class,match.arms().getFirst().pattern());
        Ast.ConstructorPattern payload=(Ast.ConstructorPattern)match.arms().getFirst().pattern();
        assertEquals("Message.Compute",payload.constructor());
        assertEquals(2,payload.arguments().size());
        // The native ActorMail<T> and enum guest ABI cannot be bypassed simply
        // because a frontend now understands the proposed source tokens.
        assertThrows(IllegalArgumentException.class,()->TypeChecker.check(ast));
    }

    @Test
    void incompleteOrMisleadingSyntaxFailsClosed() {
        List<String> invalid=List.of(
                "do match v over { on Some(n) { return n; }", // missing closing match
                "do match v over { on Some(n) => { return n; } }", // fat arrow
                "do match v over on Some(n) { return n; } end", // missing arrow in end form
                "do match v over { on Some(n) { return n; } } end", // trailing terminator
                "do match v { on Some(n) { return n; } }", // missing over
                "do match v over { on Some(n) { return n; } on None }" // missing body
        );
        for(String fragment:invalid){
            var src="fnc f(Option<int> v): int { "+fragment+" }";
            assertThrows(IllegalArgumentException.class,()->Parser.parse(src),fragment);
        }
    }
}

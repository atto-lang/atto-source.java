package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A readonly structural record may alias mutable backing storage. Element
 * types alone cannot establish an independently Send-safe owner allocation.
 */
final class ReadonlyRecordTaskCaptureTest {
    @Test
    void readonlyViewsRequireSeparateOwnershipProvenance() throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var ctor = OwnershipChecker.class.getDeclaredConstructor(
                Ast.Program.class, Map.class);
        ctor.setAccessible(true);
        var checker = ctor.newInstance(program, Map.of());
        Method forbidden = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", Ast.TypeRef.class);
        forbidden.setAccessible(true);

        Ast.TypeRef intType = Ast.TypeRef.simple("int");
        Ast.TypeRef record = Ast.TypeRef.readonlyRecordType(
                Map.of("value", intType));
        Ast.TypeRef ownedStruct = Ast.TypeRef.structType(
                Map.of("value", intType));
        Ast.TypeRef nested = Ast.TypeRef.structType(
                Map.of("view", record));
        Ast.TypeRef wrapped = new Ast.TypeRef(
                "Option", List.of(record), false);

        assertTrue((Boolean) forbidden.invoke(checker, record),
                "readonly structural view cannot be moved into independent Task");
        assertTrue((Boolean) forbidden.invoke(checker, nested),
                "owned wrapper cannot launder nested unproven record view");
        assertTrue((Boolean) forbidden.invoke(checker, wrapped),
                "Option cannot launder unproven readonly view");
        assertFalse((Boolean) forbidden.invoke(checker, ownedStruct),
                "owned inline struct with Send fields remains transferable");
        assertFalse((Boolean) forbidden.invoke(checker, intType),
                "scalar Copy capture remains transferable");
    }

    @Test
    void nominalActorAndLoanRemainNonSend() throws Exception {
        var program = Parser.parse("""
                define actor Worker as
                  receive(ActorMail<int> mail): void { return; }
                end
                pub fnc main(): void { return; }
                """);
        var ctor = OwnershipChecker.class.getDeclaredConstructor(
                Ast.Program.class, Map.class);
        ctor.setAccessible(true);
        var checker = ctor.newInstance(program, Map.of());
        Method forbidden = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", Ast.TypeRef.class);
        forbidden.setAccessible(true);

        assertTrue((Boolean) forbidden.invoke(
                checker, Ast.TypeRef.simple("Worker")));
        var loan = Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false);
        assertTrue((Boolean) forbidden.invoke(
                checker, Ast.TypeRef.structType(Map.of("loan", loan))));
    }
}

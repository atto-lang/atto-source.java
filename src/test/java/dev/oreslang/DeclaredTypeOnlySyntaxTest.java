package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class DeclaredTypeOnlySyntaxTest {
    @TempDir Path temp;

    @Test
    void acceptsAllThreeDeclaredTypeFamiliesWithPublicVisibility() {
        Ast.Program program = Parser.parse("""
                declare type LocalId = int;
                declare pub type PublicId = int;
                declare trait LocalTrait as
                  fnc visit(): void;
                end
                declare pub trait PublicTrait as
                  fnc visit(): void;
                end
                declare interface LocalApi as
                  fnc run(): void;
                end
                declare pub interface PublicApi as
                  fnc run(): void;
                end
                """);
        var declarations = program.modules().getFirst().declarations();
        assertEquals(6, declarations.size());
        assertEquals(Ast.Visibility.PRIVATE, ((Ast.TypeAliasDecl) declarations.get(0)).visibility());
        assertEquals(Ast.Visibility.PUBLIC, ((Ast.TypeAliasDecl) declarations.get(1)).visibility());
        for (int index = 2; index < 6; index++) {
            Ast.InterfaceDecl iface = assertInstanceOf(Ast.InterfaceDecl.class, declarations.get(index));
            assertEquals(index % 2 == 1 ? Ast.Visibility.PUBLIC : Ast.Visibility.PRIVATE,
                    iface.visibility());
            assertFalse(iface.moduleContract(), "traits/interfaces are not module contracts");
            assertEquals(index < 4, iface.traitDeclaration(), "source trait kind must survive parsing");
        }
    }

    @Test
    void acceptsTypeOnlyDeclarationsInModulesAndDoesNotInventRuntimeValues() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                define module api as
                  declare pub type Id = int;
                  declare pub trait Tagged as
                    fnc tag() => int;
                  end
                  declare pub interface Endpoint as
                    fnc request() => int;
                  end
                  pub fnc identity(Id id): Id { return id; }
                end
                """);
        var declarations = program.modules().getFirst().declarations();
        assertEquals(4, declarations.size());
        assertInstanceOf(Ast.TypeAliasDecl.class, declarations.get(0));
        assertInstanceOf(Ast.InterfaceDecl.class, declarations.get(1));
        assertInstanceOf(Ast.InterfaceDecl.class, declarations.get(2));
        assertInstanceOf(Ast.FunctionDecl.class, declarations.get(3));
    }

    @Test
    void preservesLegacyDefineSpellingsAndContextualDeclareIdentifier() {
        Ast.Program program = Parser.parse("""
                define type OldId = int;
                define interface OldApi as
                  fnc read() => int;
                end
                define trait OldTrait as
                  fnc read() => int;
                end
                fnc declare(int value): int { return value; }
                """);
        assertEquals(4, program.modules().getFirst().declarations().size());
        assertEquals("declare",
                ((Ast.FunctionDecl) program.modules().getFirst().declarations().getLast()).name());
    }

    @Test
    void rejectsConcreteOrUnknownDeclarationsAndDiscardedModifiers() {
        for (String source : new String[] {
                "declare class Foo as end",
                "declare actor Worker as end",
                "declare model M as Add(1) end",
                "declare fnc f(): void { return; }",
                "declare pub pub type Id = int;",
                "declare async type Id = int;",
                "declare suspends interface Foo as end",
                "declare trap trait Foo as end",
                "@Audited declare pub type Id = int;",
                "@Audited declare interface Foo as end",
                "declare interface Foo as fnc run() => int;",
                "declare trait Foo as fnc run() => int;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void typeAliasNameIsNotAnExecutableRuntimeValue() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                declare type SomeId = int;
                fnc invalid(): void {
                  val impossible = SomeId;
                  return;
                }
                """));
    }
    @Test
    void publicTypeOnlyFamiliesLinkThroughTheirImportSelectors() throws Exception {
        Path exported = temp.resolve("types.ores");
        Path imported = temp.resolve("main.ores");
        Files.writeString(exported, """
                declare pub type Id = int;
                declare pub trait Tagged as
                  fnc tag() => int;
                end
                declare pub interface Endpoint as
                  fnc request() => int;
                end
                """);
        Files.writeString(imported, """
                import type Id from './types';
                import trait Tagged from './types';
                import interface Endpoint from './types';
                pub routine main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(imported));
    }

    @Test
    void privateTypeOnlyDefinitionsCannotBeImportedAsPublic() throws Exception {
        Path exported = temp.resolve("types.ores");
        Files.writeString(exported, """
                declare type Id = int;
                declare trait Tagged as end
                declare interface Endpoint as end
                """);
        for (String selection : new String[]{
                "type Id", "trait Tagged", "interface Endpoint"
        }) {
            Path imported = temp.resolve("import-" + selection.replace(' ', '-') + ".ores");
            Files.writeString(imported, "import " + selection
                    + " from './types'; pub routine main(): void { return; }");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(imported), selection);
        }
    }


    @Test
    void declareAndDefineTypeAreExactlyEquivalentCompleteAliases() {
        Ast.Program declared = OresCompiler.parseAndTypeCheck("""
                declare type Code = int;
                fnc identity(Code value): Code { return value; }
                """);
        Ast.Program defined = OresCompiler.parseAndTypeCheck("""
                define type Code = int;
                fnc identity(Code value): Code { return value; }
                """);
        Ast.TypeAliasDecl declaredAlias =
                assertInstanceOf(Ast.TypeAliasDecl.class, declared.modules().getFirst().declarations().getFirst());
        Ast.TypeAliasDecl definedAlias =
                assertInstanceOf(Ast.TypeAliasDecl.class, defined.modules().getFirst().declarations().getFirst());
        assertEquals(definedAlias, declaredAlias,
                "declare and define are only interchangeable source introducers");
        assertEquals(Ast.TypeRef.simple("int"), declaredAlias.target());
    }

    @Test
    void declareAndDefinePublicTypeAliasesHaveTheSameIncrementalAbi() {
        String declared = new IncrementalCompiler()
                .compile(Map.of("types.ores", "declare pub type Code = int;"))
                .units().get("types.ores").abiDigest();
        String defined = new IncrementalCompiler()
                .compile(Map.of("types.ores", "define pub type Code = int;"))
                .units().get("types.ores").abiDigest();
        assertEquals(declared, defined,
                "source introducer must not alter public ABI or invalidate importers");
    }

    @Test
    void publicAndGenericTypeAliasesShareIdenticalSemantics() {
        Ast.Program declared = OresCompiler.parseAndTypeCheck("""
                define module api as
                  declare pub type Id = int;
                  fnc identity(Id value): Id { return value; }
                end
                """);
        Ast.Program defined = OresCompiler.parseAndTypeCheck("""
                define module api as
                  define pub type Id = int;
                  fnc identity(Id value): Id { return value; }
                end
                """);
        assertEquals(
                declared.modules().getFirst().declarations().getFirst(),
                defined.modules().getFirst().declarations().getFirst());

        Ast.Program generic = Parser.parse("""
                declare type Box<T> = T;
                define type OtherBox<T> = T;
                """);
        var defs = generic.modules().getFirst().declarations();
        Ast.TypeAliasDecl first = assertInstanceOf(Ast.TypeAliasDecl.class, defs.get(0));
        Ast.TypeAliasDecl second = assertInstanceOf(Ast.TypeAliasDecl.class, defs.get(1));
        assertEquals(first.target(), second.target());
        assertEquals(first.genericParameters(), second.genericParameters());
    }

    @Test
    void incompleteTypesAreAlwaysRejectedAndNeitherIntroducerCanRedefine() {
        for (String source : new String[] {
                "declare type Code;",
                "define type Code;",
                "declare pub type Code;",
                "define pub type Code;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
        for (String source : new String[] {
                "declare type Code = int; define type Code = bool;",
                "define type Code = int; declare type Code = bool;",
                "declare type Code = int; declare type Code = int;",
                "define type Code = int; define type Code = int;"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
            assertTrue(error.getMessage().contains("TYPE_DECL_NAME_COLLISION"),
                    source + ": " + error.getMessage());
        }
    }

    @Test
    void aliasesRemainTransparentAndCyclicAliasesStillReject() {
        for (String introducer : new String[] {"declare", "define"}) {
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                    introducer + " type Code = int; "
                    + "fnc identity(Code value): Code { return value; }"));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            introducer + " type A = B; "
                            + introducer + " type B = A; "
                            + "fnc identity(A value): A { return value; }"));
            assertTrue(error.getMessage().contains("type alias cycle"), error.getMessage());
        }
    }

    @Test
    void wrongKindImportsFailClosedForPublicTypeOnlyDeclarations() throws Exception {
        Path exported = temp.resolve("kinds.ores");
        Files.writeString(exported, """
                declare pub trait Tagged as end
                declare pub interface Endpoint as end
                """);
        for (String badSelection : new String[] {
                "trait Endpoint", "interface Tagged", "type Tagged", "type Endpoint"
        }) {
            Path imported = temp.resolve("wrong-" + badSelection.replace(' ', '-') + ".ores");
            Files.writeString(imported,
                    "import " + badSelection + " from './kinds';"
                    + "pub routine main(): void { return; }");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(imported), badSelection);
            assertTrue(error.getMessage().contains("does not match an exported declaration"),
                    error.getMessage());
        }
    }

    @Test
    void rejectsCrossKindInheritanceButAllowsSameKindParents() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                declare trait Tagged as end
                declare trait Derived extends Tagged as end
                declare interface Endpoint as end
                declare interface DerivedApi extends Endpoint as end
                """));

        for (String source : new String[] {
                "declare trait Parent as end declare interface Child extends Parent as end",
                "declare interface Parent as end declare trait Child extends Parent as end",
                "declare type Item = int; declare trait Item as end",
                "declare interface Item as end declare type Item = int;",
                "declare trait Item as end declare interface Item as end",
                "define class Item as end declare trait Item as end",
                "declare type Item = int; define class Item as end"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
            assertTrue(error.getMessage().contains("TYPE_PARENT_KIND_MISMATCH")
                            || error.getMessage().contains("TYPE_DECL_NAME_COLLISION"),
                    error.getMessage());
        }
    }

    @Test
    void publicTraitKindParticipatesInIncrementalAbi() {
        String body = " fnc tag() => int; end";
        IncrementalCompiler traitCompiler = new IncrementalCompiler();
        IncrementalCompiler interfaceCompiler = new IncrementalCompiler();
        String trait = traitCompiler.compile(Map.of("api.ores", "declare pub trait Tagged as" + body))
                .units().get("api.ores").abiDigest();
        String iface = interfaceCompiler.compile(Map.of("api.ores", "declare pub interface Tagged as" + body))
                .units().get("api.ores").abiDigest();
        assertNotEquals(trait, iface, "changing a public interface to a trait invalidates importers");
    }

    @Test
    void legacyTraitSpellingRetainsTraitIdentity() {
        Ast.Program program = Parser.parse("""
                define trait Legacy as end
                declare trait Canonical as end
                define interface Api as end
                """);
        var declarations = program.modules().getFirst().declarations();
        assertTrue(((Ast.InterfaceDecl) declarations.get(0)).traitDeclaration());
        assertTrue(((Ast.InterfaceDecl) declarations.get(1)).traitDeclaration());
        assertFalse(((Ast.InterfaceDecl) declarations.get(2)).traitDeclaration());
    }


    @Test
    void traitAndInterfaceNamesNeverBecomeRuntimeValues() {
        for (String declaration : new String[] {
                "declare trait Tagged as end",
                "declare interface Tagged as end"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(declaration + "\n" + """
                            fnc invalid(): void {
                              val captured = Tagged;
                              return;
                            }
                            """), declaration);
        }
    }

    @Test
    void pluralTypeImportsAllowTraitsAndInterfacesButNotModuleContracts() throws Exception {
        Path exported = temp.resolve("public-types.ores");
        Files.writeString(exported, """
                declare pub type Id = int;
                declare pub trait Tagged as end
                declare pub interface Endpoint as end
                define pub contract ModulePolicy as end
                """);
        for (String selection : new String[] {
                "Id", "Tagged", "Endpoint"
        }) {
            Path consumer = temp.resolve("consumer-" + selection + ".ores");
            Files.writeString(consumer, "import types " + selection
                    + " from './public-types'; pub routine main(): void { return; }");
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(consumer), selection);
        }
        Path illegal = temp.resolve("contract-consumer.ores");
        Files.writeString(illegal,
                "import types ModulePolicy from './public-types';"
                + " pub routine main(): void { return; }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(illegal));
        assertTrue(error.getMessage().contains("does not match an exported declaration"),
                error.getMessage());
    }
}

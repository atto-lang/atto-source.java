package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class RecursiveValueGraphHardeningTest {
    @Test
    void recursiveStructTypeUsesCanonicalExplicitSpelling() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Node = struct{
                  value: int,
                  next: Option<Node>
                };

                fnc accept(Node node): void {
                  return;
                }

                fnc follow(Node node): Node {
                  val Option<Node> next = node.next;
                  return next.unwrap();
                }

                fnc wrap(Node node): Option<Node> {
                  return Some(node);
                }
                """)));
    }

    @Test
    void unguardedAliasAndNonRegularRecursionStillFail() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("type A = B; type B = A;")));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("type A = A | int;")));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("type Grow<T> = Option<Grow<List<T>>>;")));
    }

    @Test
    void uninhabitableRequiredRecursiveFieldsAreRejected() {
        for (String impossible : new String[]{
                "type Bad = struct{ next: Bad };",
                "type Bad = Result<Bad, Bad>;",
                "type Bad = [int, Bad];",
                "type Bad = Tuple[Bad];"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(impossible)),
                    impossible);
            assertTrue(failure.getMessage().contains("finite base")
                    || failure.getMessage().contains("cycle"), failure.getMessage());
        }
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Node = struct{
                  value: int,
                  next: Option<Node>
                };
                """)));
    }

    @Test
    void cyclicClassTypeCannotBeMistakenForSendableActorMessage() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        type Node = Option<Node>;
                        actor fnc send(Node value): void {
                          return;
                        }
                        """)));
    }

    @Test
    void importedModulesClassesAndWildcardPackagesRemainStatic() {
        for (String declaration : new String[]{
                "import module Library from './dependency';",
                "import class Library from './dependency';",
                "import * as Library from './dependency';"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                            declaration + "\nfnc bad(): void { val value = Library; return; }")),
                    declaration);
            assertTrue(failure.getMessage().contains("static"), failure.getMessage());
        }
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                import module Library from './dependency';
                fnc ok(): void {
                  Library.member();
                  return;
                }
                """)));
    }

    @Test
    void displayHandlesLocalCyclesAndSharedSubtreesWithoutStackOverflow() throws Exception {
        Class<?> objectType =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$OresObject");
        Constructor<?> ctor = objectType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);

        Ast.ClassDecl decl = (Ast.ClassDecl) Parser.parse("""
                define class Node as
                end
                """).modules().getFirst().declarations().getFirst();

        Map<String, Object> fields = new LinkedHashMap<>();
        Object node = ctor.newInstance(null, decl, fields);
        fields.put("self", node);
        fields.put("value", 1L);

        String printed = String.valueOf(node);
        assertTrue(printed.contains("<circular>"), printed);
        assertTrue(printed.contains("Node"), printed);

        Class<?> renderer =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$GraphDisplay");
        Method format = renderer.getDeclaredMethod("format", Object.class);
        format.setAccessible(true);
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertTrue(((String) format.invoke(null, cyclic)).contains("<circular>"));

        List<Object> shared = new ArrayList<>();
        shared.add("x");
        List<Object> acyclicAlias = List.of(shared, shared);
        assertEquals("[[x], [x]]", format.invoke(null, acyclicAlias));

        Object deep = "leaf";
        for (int i = 0; i < 100; i++) {
            List<Object> next = new ArrayList<>();
            next.add(deep);
            deep = next;
        }
        assertTrue(((String) format.invoke(null, deep)).contains("<truncated>"));

    }

    @Test
    void movingOwnerIntoOwnFieldIsRejectedAfterRhsConsumption() {
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class, () ->
                dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                        define class Link as
                          pub let Option<Link> next = None;
                        end
                        fnc invalid(): void {
                          let mut Link node = new Link();
                          node.next = Some(node);
                          return;
                        }
                        """)));
        assertTrue(moved.getMessage().contains("moved"), moved.getMessage());
    }

    @Test
    void recursiveRecordsNeverAcquireImplicitCopyThroughAliases() {
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class, () ->
                dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                        type Node = struct{value: int, next: Option<Node>};
                        fnc consume(Node node): void { return; }
                        fnc bad(Node node): void {
                          consume(node);
                          consume(node);
                          return;
                        }
                        """)));
        assertTrue(moved.getMessage().contains("moved"), moved.getMessage());
        assertDoesNotThrow(() -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                type Flag = bool;
                fnc consume(Flag flag): void { return; }
                fnc okay(): void {
                  val Flag flag = true;
                  consume(flag);
                  consume(flag);
                  return;
                }
                """)));
    }

    @Test
    void classFieldsCannotHideBorrowedReferencesBehindAliasOrContainer() {
        for (String source : new String[]{
                """
                define class Link as
                  let Option<&Link> direct;
                end
                """,
                """
                type Borrowed = &int;
                define class Link as
                  let Borrowed hidden;
                end
                """,
                """
                type Borrowed = &int;
                type Nested = Option<Borrowed>;
                define class Link as
                  let Nested hidden;
                end
                """
        }) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(source)), source);
            assertTrue(rejected.getMessage().contains("borrow"), rejected.getMessage());
        }
    }

    @Test
    void mutableCyclesStayLocalAndCannotCrossActorOrSharedMutexBoundary() throws Exception {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(cycle));

        Class<?> mutexFactory =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$MutexFactory");
        Method safe = mutexFactory.getDeclaredMethod(
                "runtimeSharedSafe", Object.class, Set.class);
        safe.setAccessible(true);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        assertEquals(false, safe.invoke(null, cycle, seen));

        // Reusing an acyclic subtree is aliasing, not a cycle.
        List<Object> shared = new ArrayList<>();
        shared.add("ok");
        List<Object> dag = List.of(shared, shared);
        assertEquals(true, safe.invoke(null, dag,
                Collections.newSetFromMap(new IdentityHashMap<>())));
        assertDoesNotThrow(() -> ActorRuntime.freeze(dag));
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Imported actor metadata is a checked mailbox protocol type, NOT an
 * actor-state instance and never a loose name-based Send permission.
 */
final class LinkedActorTaskCaptureV2Test {
    private static Ast.ClassDecl remoteActor(
            Ast.Visibility visibility, Ast.ActorKind kind) {
        return new Ast.ClassDecl(
                "RemoteWorker", visibility, false, kind,
                List.of(), List.of(), List.of(), List.of(),
                null, List.of());
    }

    private static Ast.Program source(String importedKind) {
        return Parser.parse("import " + importedKind
                + " RemoteWorker from \"remote-worker.ores\";\n"
                + """
                  pub async fnc forward(ActorRef<RemoteWorker> mailbox): void {
                    let Future<void> child = task {
                      val preserved = mailbox;
                      return;
                    };
                    await child;
                    return;
                  }
                  """);
    }

    @Test
    void exactImportedActorSignatureAllowsCheckedMailboxTaskCapture() {
        Ast.Program imported = source("actor");
        Map<String, Ast.ClassDecl> signatures =
                Map.of("RemoteWorker",
                        remoteActor(Ast.Visibility.PUBLIC, Ast.ActorKind.SHARED));
        TypeChecker.TypeCheckResult typed =
                TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        imported, signatures, Map.of());
        Ast.Program checked = OwnershipChecker.checkWithImportedClasses(
                typed.program(), signatures, typed.shortInferredAliases());

        Ast.TaskExpr child = null;
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl function
                        && function.name().equals("forward")) {
                    for (Ast.Stmt stmt : function.body()) {
                        if (stmt instanceof Ast.BindingStmt binding
                                && binding.initializer() instanceof Ast.TaskExpr task) {
                            child = task;
                        }
                    }
                }
            }
        }
        assertNotNull(child, "test must locate the independently scheduled Task");
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertEquals(
                Map.of("mailbox", Ast.TaskCaptureTransfer.COPY),
                Ast.CheckedTaskCaptures.require(
                        checked, child, epoch).captureTransfers());
    }

    @Test
    void actorMetadataCannotBeLinkedThroughClassImport() {
        Ast.Program program = source("class");
        Map<String, Ast.ClassDecl> metadata =
                Map.of("RemoteWorker",
                        remoteActor(Ast.Visibility.PUBLIC, Ast.ActorKind.SHARED));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        program, metadata, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithImportedClasses(program, metadata));
    }

    @Test
    void ordinaryClassCannotMasqueradeAsImportedActorSignature() {
        Ast.Program program = source("actor");
        Map<String, Ast.ClassDecl> metadata =
                Map.of("RemoteWorker",
                        remoteActor(Ast.Visibility.PUBLIC, Ast.ActorKind.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        program, metadata, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithImportedClasses(program, metadata));
    }

    @Test
    void mailboxCopyAndTaskSendAgreeOnRealActorVersusUnverifiedTarget()
            throws Exception {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  receive(ActorMail<int> mail): void { return; }
                end
                pub fnc main(): void { return; }
                """);
        var ctor = OwnershipChecker.class.getDeclaredConstructor(
                Ast.Program.class, Map.class);
        ctor.setAccessible(true);
        var checker = ctor.newInstance(program, Map.of());
        var copyGate = OwnershipChecker.class.getDeclaredMethod(
                "isCopyType", Ast.TypeRef.class);
        copyGate.setAccessible(true);
        var sendGate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", Ast.TypeRef.class);
        sendGate.setAccessible(true);

        for (String target : List.of("Worker", "UnverifiedActor")) {
            Ast.TypeRef handle = new Ast.TypeRef("ActorRef",
                    List.of(Ast.TypeRef.simple(target)), false);
            boolean valid = target.equals("Worker");
            assertEquals(valid, copyGate.invoke(checker, handle),
                    "ActorRef Copy must use verified actor provenance");
            assertEquals(!valid, sendGate.invoke(checker, handle),
                    "ActorRef Task Send must match checked mailbox provenance");
        }
    }

    @Test
    void localAliasesCannotAcquireBuiltinMailboxOrChannelAuthority()
            throws Exception {
        Ast.Program source = Parser.parse("""
                define actor Worker as
                  receive(ActorMail<int> mail): void { return; }
                end
                pub fnc main(): void { return; }
                """);
        var modules = new java.util.ArrayList<Ast.ModuleDecl>();
        for (Ast.ModuleDecl module : source.modules()) {
            var decls = new java.util.ArrayList<Ast.Decl>(
                    module.declarations());
            decls.add(new Ast.TypeAliasDecl("ActorRef",
                    Ast.Visibility.PRIVATE, List.of(),
                    Ast.TypeRef.simple("int")));
            decls.add(new Ast.TypeAliasDecl("Channel",
                    Ast.Visibility.PRIVATE, List.of(),
                    Ast.TypeRef.simple("int")));
            decls.add(new Ast.TypeAliasDecl("Option",
                    Ast.Visibility.PRIVATE, List.of(),
                    Ast.TypeRef.simple("int")));
            decls.add(new Ast.TypeAliasDecl("Result",
                    Ast.Visibility.PRIVATE, List.of(),
                    Ast.TypeRef.simple("int")));
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(),
                    module.contracts(), decls));
        }
        Ast.Program withAliases = new Ast.Program(
                source.namespace(), source.imports(), modules);
        var ctor = OwnershipChecker.class.getDeclaredConstructor(
                Ast.Program.class, Map.class);
        ctor.setAccessible(true);
        var checker = ctor.newInstance(withAliases, Map.of());
        var copy = OwnershipChecker.class.getDeclaredMethod(
                "isCopyType", Ast.TypeRef.class);
        copy.setAccessible(true);
        var send = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", Ast.TypeRef.class);
        send.setAccessible(true);

        Ast.TypeRef claimedMailbox = new Ast.TypeRef("ActorRef",
                List.of(Ast.TypeRef.simple("Worker")), false);
        Ast.TypeRef claimedChannel = new Ast.TypeRef("Channel",
                List.of(Ast.TypeRef.simple("int")), false);
        Ast.TypeRef claimedOption = new Ast.TypeRef("Option",
                List.of(Ast.TypeRef.simple("int")), false);
        Ast.TypeRef claimedResult = new Ast.TypeRef("Result",
                List.of(Ast.TypeRef.simple("int"),
                        Ast.TypeRef.simple("int")), false);
        for (Ast.TypeRef unproven :
                List.of(claimedMailbox, claimedChannel,
                        claimedOption, claimedResult)) {
            assertEquals(false, copy.invoke(checker, unproven),
                    "shadowed runtime builtin must not be Copy");
            assertEquals(true, send.invoke(checker, unproven),
                    "shadowed runtime builtin must not be Task Send");
        }
    }

    @Test
    void privateLinkedActorIsNotAnAuthorizedPublicImport() {
        Ast.Program program = source("actor");
        Map<String, Ast.ClassDecl> metadata =
                Map.of("RemoteWorker",
                        remoteActor(Ast.Visibility.PRIVATE, Ast.ActorKind.PRIVATE));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        program, metadata, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithImportedClasses(program, metadata));
    }
}

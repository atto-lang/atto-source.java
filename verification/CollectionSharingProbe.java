package dev.oreslang.types;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.*;
public final class CollectionSharingProbe {
  static int checks;
  static void require(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  static void compile(String source) { OwnershipChecker.check(TypeChecker.check(Parser.parse(source))); }
  static void rejects(String source, String diagnostic) {
    try { compile(source); throw new AssertionError("accepted unsafe source"); }
    catch (IllegalArgumentException e) { require(e.getMessage().contains(diagnostic), e.getMessage()); }
  }
  public static void main(String[] args) {
    Types.Type scalar = Types.Primitive.INT;
    var view = new Types.Record(Map.of("value", scalar), true);
    for (Types.Type type : List.of(view, new Types.Record(Map.of(), true),
        new Types.Tuple(List.of(view)), new Types.Named("Option", List.of(view)),
        new Types.Named("Result", List.of(scalar, view)),
        new Types.Named("Arc", List.of(view)), new Types.Named("Weak", List.of(view)))) {
      var caps = TypeAutoTraits.inferClosed(type);
      require(!caps.sync(), "readonly view gained Sync: " + type);
      require(!caps.share(), "readonly view gained Share: " + type);
    }
    for (Types.Type type : List.of(new Types.ListType(scalar),
        new Types.Tuple(List.of(scalar), Types.TupleKind.FIXED_ARRAY),
        new Types.Tuple(List.of(scalar), Types.TupleKind.FIXED_LIST), view)) {
      var owned = TypeAutoTraits.inferClosed(type);
      require(owned.send() && !owned.sync() && !owned.share(), "unsafe owned capabilities");
      var locked = TypeAutoTraits.inferClosed(new Types.Named("SharedMutex", List.of(type)));
      require(locked.send() && locked.sync() && locked.share(), "lost synchronized protection");
      require(ThreadSafetyTraits.has(type, ThreadSafetyTraits.Trait.SEND, (t,k)->false), "lost transfer");
      require(!ThreadSafetyTraits.has(type, ThreadSafetyTraits.Trait.SYNC, (t,k)->false), "trait disagreement");
    }
    var unsafe = new Types.ListType(new Types.Named("Rc", List.of(scalar)));
    require(!TypeAutoTraits.inferClosed(unsafe).send(), "unsafe element transferred");
    require(!TypeAutoTraits.inferClosed(new Types.Named("SharedMutex", List.of(unsafe))).share(), "lock blessed unsafe element");
    var program = Parser.parse("pub async fnc main(): void { let Future<int> child = task { return 1; }; return; }");
    Ast.TaskExpr task = null;
    for (var module : program.modules()) for (var decl : module.declarations())
      if (decl instanceof Ast.FunctionDecl fn) for (var stmt : fn.body())
        if (stmt instanceof Ast.BindingStmt bind && bind.initializer() instanceof Ast.TaskExpr t) task=t;
    require(task != null, "missing task");
    for (Types.Type type : List.of(view, new Types.Record(Map.of(), true))) {
      try {
        ScopedTaskReadAuthority.freezeForOwnershipCheck(program, task, 991,
          new ScopedTaskReadAuthority.AllocationIdentity("PROCESS", "root", 27, 1, true), List.of(), type);
        throw new AssertionError("readonly view minted READ authority");
      } catch (IllegalArgumentException e) { require(e.getMessage().contains("Sync"), e.getMessage()); }
    }
    compile("pub fnc main(): void { val values = [1, 2]; values.set(0, 3); return; }"); checks++;
    compile("pub fnc main(): void { val mutex = SharedMutex.new([10]); val guard = mutex.lock(); val before = guard.get(0); guard.set(0, before + 1); guard.release(); return; }"); checks++;
    rejects("pub fnc main(): void { val values = [1, 2]; val shared = rt share values; return; }", "rt share");
    rejects("pub fnc main(): void { val values = [1, 2]; val other = rt take values; values.get(0); return; }", "moved");
    rejects("pub async fnc main(): void { val mutex = SharedMutex.new([10]); val guard = mutex.lock(); await mutex.lock_async(); guard.release(); return; }", "cannot await");
    System.out.println("PASS: " + checks + " collection sharing / scoped READ / frontend checks");
  }
}

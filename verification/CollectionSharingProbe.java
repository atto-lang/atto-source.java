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
    // A readonly structural view may be assigned from writable storage;
    // the writer is not revoked by the type conversion.
    var writable = new Types.Record(Map.of("value", scalar), false);
    require(Types.isAssignable(writable, view), "expected writable -> readonly view");
    require(!Types.isAssignable(view, writable), "readonly view recovered write access");
    require(!TypeAutoTraits.inferClosed(view).sync(), "readonly conversion minted Sync");
    require(!TypeAutoTraits.inferClosed(view).share(), "readonly conversion minted Share");
    require(!ThreadSafetyTraits.has(new Types.Borrow(view, false),
        ThreadSafetyTraits.Trait.SEND, (t,k)->false),
        "borrowed readonly alias crossed thread boundary");
    // Exercise wrapper composition and the guest source-admission path, not
    // just a direct call to the auto-trait classifier.
    for (Types.Type wrapper : List.of(
        new Types.Named("Option", List.of(view)),
        new Types.Named("Result", List.of(scalar, view)),
        new Types.Named("Arc", List.of(view)),
        new Types.Named("Weak", List.of(view)))) {
      var caps = TypeAutoTraits.inferClosed(wrapper);
      require(!caps.sync() && !caps.share(), "wrapper laundered readonly view");
    }
    compile("pub fnc main(): int { const row = record{value: 1}; return row.value; }"); checks++;
    rejects("pub fnc main(): void { const row = record{value: 1}; val shared = rt share row; return; }", "rt share");
    rejects("pub async fnc main(): void { val owner = infer struct{value: 1}; val view = rt borrow owner; let Future<int> child = task { return view.value; }; return; }", "borrow");
    rejects("pub fnc main(): void { val owner = [10]; val lock = SharedMutex.new(owner); owner.get(0); return; }", "moved");
    rejects("pub fnc main(): void { val owner = [10]; val view = rt borrow owner; val lock = SharedMutex.new(view); return; }", "borrow");
    // A structural readonly parameter can be borrowed from mutable caller
    // storage. The caller keeps its writer; a local synchronous read is safe,
    // but publishing the borrowed view into an independent Task is not.
    compile("""
        fnc inspect(structural {value: int} view): int {
          return view.value;
        }
        pub fnc main(): int {
          val owner = infer struct{value: 1};
          val before = inspect(owner);
          owner.value = 2;
          return before;
        }
        """); checks++;
    rejects("""
        fnc leak(structural {value: int} view): Future<int> {
          return task { return view.value; };
        }
        pub fnc main(): void {
          val owner = infer struct{value: 1};
          val child = leak(owner);
          owner.value = 2;
          return;
        }
        """, "borrow");
    rejects("""
        fnc illicit_publish(structural {value: int} view): void {
          val shared = rt share view;
          return;
        }
        pub fnc main(): void {
          val owner = infer struct{value: 1};
          illicit_publish(owner);
          owner.value = 2;
          return;
        }
        """, "rt share");
    // Some handle/wrapper shapes are generally Sync-capable, but a scoped
    // cross-thread READ loan must not bypass the independent runtime proof.
    var handle = new Types.Named("SharedMutex", List.of(scalar));
    for (Types.Type referent : List.of(
        handle,
        new Types.Named("Option", List.of(handle)),
        new Types.Named("Result", List.of(scalar, handle)),
        new Types.Named("ActorRef", List.of(scalar)),
        new Types.Named("Arc", List.of(scalar)),
        new Types.Named("Weak", List.of(scalar)),
        new Types.Named("Box", List.of(scalar)))) {
      require(TypeAutoTraits.inferClosed(referent).sync(), "expected general Sync");
      require(!ThreadSafetyTraits.has(referent,
          ThreadSafetyTraits.Trait.SYNC, (named, trait)->false),
          "unverified wrapper acquired runtime Sync proof");
      try {
        ScopedTaskReadAuthority.freezeForOwnershipCheck(program, task, 991,
            new ScopedTaskReadAuthority.AllocationIdentity("PROCESS", "root", 27, 1, true),
            List.of(), referent);
        throw new AssertionError("unverified wrapper minted scoped READ proof");
      } catch (IllegalArgumentException e) {
        require(e.getMessage().contains("verified Sync"), e.getMessage());
      }
    }
    // Immutable inline scalar tuples retain the verified fast path.
    var tuple = new Types.Tuple(List.of(scalar));
    require(ThreadSafetyTraits.has(tuple,
        ThreadSafetyTraits.Trait.SYNC, (named, trait)->false),
        "lost verified immutable tuple proof");
    require(ScopedTaskReadAuthority.freezeForOwnershipCheck(program, task, 991,
        new ScopedTaskReadAuthority.AllocationIdentity("PROCESS", "root", 27, 1, true),
        List.of(), tuple) != null, "lost scalar tuple READ admission");
    // Do not feed attacker-sized metadata into recursive auto-trait
    // inference before the bounded independent thread-safety proof.
    Types.Type deep = scalar;
    for (int i = 0; i < 4096; i++) {
      deep = new Types.Named("Option", List.of(deep));
    }
    try {
      ScopedTaskReadAuthority.freezeForOwnershipCheck(program, task, 991,
          new ScopedTaskReadAuthority.AllocationIdentity("PROCESS", "root", 27, 1, true),
          List.of(), deep);
      throw new AssertionError("deep type forged READ authority");
    } catch (IllegalArgumentException e) {
      require(e.getMessage().contains("verified Sync"), e.getMessage());
    }
    System.out.println("PASS: " + checks + " collection sharing / scoped READ / frontend checks");
  }
}

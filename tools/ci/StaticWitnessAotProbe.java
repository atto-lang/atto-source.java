// Standalone compiler-helper verification: no Graal, runtime Truffle or guest JVM.
import dev.oreslang.compiler.StaticWitnessAotPlan;
import dev.oreslang.types.TypeclassRegistry;
import java.util.List;

public final class StaticWitnessAotProbe {
    private static int checks;

    private static TypeclassRegistry.TypeRef named(String name) {
        return TypeclassRegistry.TypeRef.named("core",name);
    }
    private static void check(boolean trueValue,String why) {
        ++checks;
        if(!trueValue) throw new AssertionError(why);
    }
    private static void reject(Runnable action,String fragment) {
        ++checks;
        try { action.run(); }
        catch(IllegalArgumentException expected) {
            if(expected.getMessage().contains(fragment)) return;
            throw new AssertionError("Wrong static witness rejection: "+expected,expected);
        }
        throw new AssertionError("Accepted forbidden AOT specialization: "+fragment);
    }
    public static void main(String[] ignored) {
        var numeric=new TypeclassRegistry.Typeclass("algebra","Numeric",1);
        var coherence=new TypeclassRegistry.Registry();
        coherence.define(numeric);
        var integer=new TypeclassRegistry.Instance(numeric,"algebra",
                List.of(named("int")),"algebra.int_numeric");
        var booleanInstance=new TypeclassRegistry.Instance(numeric,"algebra",
                List.of(named("bool")),"algebra.bool_numeric");
        coherence.register(integer);
        coherence.register(booleanInstance);

        var first=new StaticWitnessAotPlan.Builder();
        var selected=first.add(coherence,numeric,List.of(named("int")),"root.apply");
        check(selected.dictionarySymbol().equals("algebra.int_numeric"),
                "wrong static registry witness");
        check(selected.targetSymbol().matches("root\\.apply\\$witness\\$[0-9a-f]{64}"),
                "missing deterministic precompiled specialization identity");
        check(first.add(coherence,numeric,List.of(named("int")),"root.apply")==selected,
                "duplicate ground static witness not deduplicated");
        var other=first.add(coherence,numeric,List.of(named("bool")),"root.apply");
        check(!other.targetSymbol().equals(selected.targetSymbol()),
                "distinct ground witness ABI reused one specialized target");
        check(first.close().size()==2,"incorrect specialization count");
        var reverse=new StaticWitnessAotPlan.Builder();
        reverse.add(coherence,numeric,List.of(named("bool")),"root.apply");
        reverse.add(coherence,numeric,List.of(named("int")),"root.apply");
        check(first.close().equals(reverse.close()),
                "specialization target set changed with witness source/import order");

        reject(()->first.add(coherence,numeric,
                List.of(TypeclassRegistry.TypeRef.variable("T")),"root.apply"),
                "fully ground");
        reject(()->first.add(coherence,numeric,List.of(),"root.apply"),"arity");
        reject(()->first.add(coherence,numeric,List.of(named("String")),"root.apply"),
                "missing typeclass instance");
        reject(()->first.add(coherence,numeric,List.of(named("int")),"root.apply;evil"),
                "canonical static identity");
        check(first.close().size()==2,"failed static admission mutated the plan");

        var colliding=new TypeclassRegistry.Registry();
        colliding.define(numeric);
        colliding.register(new TypeclassRegistry.Instance(numeric,"algebra",
                List.of(named("int")),"algebra.same"));
        colliding.register(new TypeclassRegistry.Instance(numeric,"algebra",
                List.of(named("bool")),"algebra.same"));
        var planner=new StaticWitnessAotPlan.Builder();
        planner.add(colliding,numeric,List.of(named("int")),"root.apply");
        reject(()->planner.add(colliding,numeric,List.of(named("bool")),"root.apply"),
                "reuse one dictionary ABI");
        check(planner.close().size()==1,"dictionary collision partially published a plan");

        var tooDeep=named("int");
        for(int i=0;i<49;i++) tooDeep=TypeclassRegistry.TypeRef.named("core","List",tooDeep);
        final var nested=tooDeep;
        reject(()->first.add(coherence,numeric,List.of(nested),"root.apply"),
                "type graph exceeds proof budget");

        var generic=new TypeclassRegistry.Typeclass("algebra","Identity",1);
        var genericRegistry=new TypeclassRegistry.Registry();
        genericRegistry.define(generic);
        genericRegistry.register(new TypeclassRegistry.Instance(generic,"algebra",
                List.of(TypeclassRegistry.TypeRef.variable("T")),"algebra.identity"));
        var many=new StaticWitnessAotPlan.Builder();
        for(int i=0;i<4096;i++)
            many.add(genericRegistry,generic,List.of(named("T"+i)),"root.identity");
        check(many.close().size()==4096,"specialization cap rejected finite 4096-item set");
        reject(()->many.add(genericRegistry,generic,List.of(named("T4096")),
                "root.identity"),"specialization budget");
        check(many.close().size()==4096,"overflow admission published a partial entry");

        System.out.println("JAVA_STATIC_WITNESS_AOT_PLAN_PASS|checks="+checks+
                "|specializations=4096|runtime_registry=false|guest_native_image=false");
    }
}

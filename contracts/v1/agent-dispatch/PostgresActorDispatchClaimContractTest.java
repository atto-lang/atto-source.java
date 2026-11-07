// JDBC host-only contract test without a server. Does not prove PostgreSQL SQL.
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
public final class PostgresActorDispatchClaimContractTest {
    static void check(boolean b){if(!b)throw new AssertionError("JDBC_CLAIM_CONTRACT");}
    static Object zero(Class<?> type){
        if(type==boolean.class)return false;
        if(type==int.class)return 0;
        if(type==long.class)return 0L;
        return null;
    }
    static <T>T proxy(Class<T> type,InvocationHandler handler){
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    static class Fake {
        boolean auto=true,readOnly=false,accepted=true,fail=false;
        int connections=0,executions=0;final Map<Integer,Object> params=new HashMap<>();
        final DataSource source=proxy(DataSource.class,(p,m,args)->{
            if(m.getName().equals("getConnection")){connections++;return connection();}
            return zero(m.getReturnType());
        });
        Connection connection(){return proxy(Connection.class,(p,m,args)->{
            switch(m.getName()){
                case "getAutoCommit":return auto;
                case "isReadOnly":return readOnly;
                case "prepareStatement":
                    check(((String)args[0]).startsWith("SELECT ores_dispatch.claim_once_v1("));
                    return statement();
                default:return zero(m.getReturnType());
            }
        });}
        PreparedStatement statement(){return proxy(PreparedStatement.class,(p,m,args)->{
            String n=m.getName();
            if(n.startsWith("set")&&args!=null&&args.length==2&&args[0] instanceof Integer idx){
                params.put(idx,args[1]);return null;
            }
            if(n.equals("executeQuery")){
                executions++;
                if(fail)throw new SQLException("unknown DB commit");
                return result();
            }
            return zero(m.getReturnType());
        });}
        ResultSet result(){final int[] row={0};return proxy(ResultSet.class,(p,m,args)->{
            switch(m.getName()){
                case "next":return ++row[0]==1;
                case "getBoolean":return accepted;
                case "wasNull":return false;
                default:return zero(m.getReturnType());
            }
        });}
    }
    public static void main(String[] unused){
        var s=new AgentHostDispatchGate.Scope("tenant","worker",7,9,"a".repeat(40),
            "dispatch","b".repeat(64),9_999_999_999_999L,1024,24);
        Fake h=new Fake();var claim=new PostgresActorDispatchClaim(h.source);
        check(claim.claimOnce(s));
        check(h.executions==1&&h.params.size()==10);
        check(h.params.get(3).equals("dispatch")&&h.params.get(4).equals("b".repeat(64)));
        check(h.params.get(5).equals(7L)&&h.params.get(8).equals(9_999_999_999_999L));
        check(h.params.get(9).equals(1024)&&h.params.get(10).equals(24));
        h.accepted=false;check(!claim.claimOnce(s));
        h.fail=true;int prior=h.executions;check(!claim.claimOnce(s));
        check(h.executions==prior+1); // never retry an ambiguous commit
        h.fail=false;h.auto=false;check(!claim.claimOnce(s));
        h.auto=true;h.readOnly=true;check(!claim.claimOnce(s));
        h.readOnly=false;prior=h.connections;
        check(!claim.claimOnce(null));
        check(h.connections==prior);
        System.out.println("POSTGRES_JDBC_HOST_CONTRACT_PASS no_database_execution");
    }
}

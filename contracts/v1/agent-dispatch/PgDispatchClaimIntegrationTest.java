// Standalone host-only PostgreSQL test. Runtime needs pgjdbc on classpath.
// Test-only DB credentials are supplied by the trusted test harness.
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;

public final class PgDispatchClaimIntegrationTest {
    private static final class DriverDataSource implements DataSource {
        private final String url, user, pass;
        DriverDataSource(String url, String user, String pass) {
            this.url=url;this.user=user;this.pass=pass;
        }
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url,user,pass);
        }
        @Override public Connection getConnection(String u,String p) throws SQLException {
            return DriverManager.getConnection(url,u,p);
        }
        @Override public PrintWriter getLogWriter(){return null;}
        @Override public void setLogWriter(PrintWriter w){}
        @Override public void setLoginTimeout(int s) throws SQLException {
            DriverManager.setLoginTimeout(s);
        }
        @Override public int getLoginTimeout(){return DriverManager.getLoginTimeout();}
        @Override public Logger getParentLogger(){return Logger.getGlobal();}
        @Override public <T>T unwrap(Class<T> x) throws SQLException {throw new SQLException("no unwrap");}
        @Override public boolean isWrapperFor(Class<?> x){return false;}
    }
    private static void check(boolean ok) {if(!ok) throw new AssertionError("PG_CLAIM_CONTRACT_FAILED");}
    private static AgentHostDispatchGate.Scope request(String id,String digest) {
        return new AgentHostDispatchGate.Scope("tenant","worker",7,9,
            "a".repeat(40),id,digest,
            System.currentTimeMillis()+300_000,1024,24);
    }
    public static void main(String[] args) throws Exception {
        String url=Objects.requireNonNull(System.getenv("ORES_TEST_PG_JDBC_URL"));
        String user=Objects.requireNonNull(System.getenv("ORES_TEST_PG_USER"));
        String pass=Objects.requireNonNull(System.getenv("ORES_TEST_PG_PASSWORD"));
        var db=new PostgresActorDispatchClaim(new DriverDataSource(url,user,pass));
        if (args.length>0 && (args[0].equals("probe") || args[0].equals("probe-hold") ||
                args[0].equals("probe-newgen"))) {
            if(args.length!=2) throw new IllegalArgumentException("probe dispatch id");
            var candidate=request(args[1],"b".repeat(64));
            if (args[0].equals("probe-newgen"))
                candidate=new AgentHostDispatchGate.Scope("tenant","worker",8,10,
                    "c".repeat(40),args[1],"b".repeat(64),
                    System.currentTimeMillis()+300_000,1024,24);
            if (!db.claimOnce(candidate)) System.exit(2);
            if (args[0].equals("probe-hold")) {
                System.out.println("CLAIM_COMMITTED");
                System.out.flush();
                Thread.sleep(30_000);
            }
            System.exit(0);
        }
        // Database has a supervisor-installed fence for tenant/worker gen7/epoch9.
        String prefix=args.length==0?"java-db-v1":args[0];
        check(!db.claimOnce(new AgentHostDispatchGate.Scope("tenant","unfenced",7,9,
            "a".repeat(40),prefix+"-unfenced","b".repeat(64),
            System.currentTimeMillis()+300_000,1024,24)));
        check(!db.claimOnce(new AgentHostDispatchGate.Scope("unbound-tenant","worker",7,9,
            "a".repeat(40),prefix+"-unbound","b".repeat(64),
            System.currentTimeMillis()+300_000,1024,24)));
        check(!db.claimOnce(new AgentHostDispatchGate.Scope("tenant","worker",8,9,
            "a".repeat(40),prefix+"-stale","b".repeat(64),
            System.currentTimeMillis()+300_000,1024,24)));
        check(!db.claimOnce(new AgentHostDispatchGate.Scope("tenant","worker",7,9,
            "a".repeat(40),prefix+"-expired","b".repeat(64),1,1024,24)));
        var req=request(prefix+"-contend","b".repeat(64));
        ExecutorService executor=Executors.newFixedThreadPool(16);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<100;++i) futures.add(executor.submit(()->db.claimOnce(req)));
            int wins=0;
            for(var f:futures) if(f.get(45,TimeUnit.SECONDS))++wins;
            check(wins==1);
        } finally {
            executor.shutdownNow();
        }
        check(!db.claimOnce(request(prefix+"-contend","c".repeat(64))));
        System.out.println("PG_DURABLE_CLAIM_PASS 100_java_clients_one_winner=true");
    }
}

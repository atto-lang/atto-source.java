package dev.oreslang.runtime.persistence;

import java.util.ArrayList;
import java.util.List;
import dev.oreslang.runtime.persistence.SqliteCommandPersistence.*;

/** Zero-dependency exact-constructor/ownership contract checks (no JDBC driver needed). */
public final class SqliteCommandIdentityTestMain {
    private static int checks;
    private SqliteCommandIdentityTestMain() {}
    private static void check(boolean yes,String message) {if(!yes)throw new AssertionError(message);checks++;}
    private static void rejects(String code,Runnable action) {
        try {action.run();throw new AssertionError("expected "+code);}
        catch(CommandError error) {check(code.equals(error.code),"unexpected error "+error.code);}
    }
    public static void main(String[] args) {
        Identity id=new Identity("tenant-a","actor-1","main");
        byte[] command={1,2,3},event={4,5,6},result={7,8};
        ArrayList<byte[]> original=new ArrayList<>();original.add(event);
        Command owned=new Command(id,"deposit-1",1,0,1,command,original,result);
        command[0]=99;event[0]=99;result[0]=99;original.clear();
        check(owned.commandBytes()[0]==1,"command was not owned");
        check(owned.events().get(0)[0]==4,"event was not owned");
        check(owned.resultBytes()[0]==7,"result was not owned");
        owned.commandBytes()[0]=55;owned.events().get(0)[0]=55;owned.resultBytes()[0]=55;
        check(owned.commandBytes()[0]==1,"command accessor leaked a write alias");
        check(owned.events().get(0)[0]==4,"event accessor leaked a write alias");
        check(owned.resultBytes()[0]==7,"result accessor leaked a write alias");
        Receipt receipt=new Receipt("deposit-1",1,2,2,new byte[]{9,8},false);
        receipt.resultBytes()[0]=55;
        check(receipt.resultBytes()[0]==9,"receipt accessor leaked owned bytes");
        rejects("INVALID_IDENTITY",()->new Identity("tenant-a","bad\nactor","main"));
        rejects("INVALID_IDENTITY",()->new Identity("tenant-a","e\u0301","main"));
        rejects("INVALID_COMMAND",()->new Command(id,"bad",1,0,1,new byte[0],List.of(),new byte[0]));
        rejects("INVALID_COMMAND",()->new Command(id,"bad",0,0,1,new byte[0],List.of(new byte[0]),new byte[0]));
        rejects("INVALID_COMMAND",()->new Command(id,"bad",1,-1,1,new byte[0],List.of(new byte[0]),new byte[0]));
        rejects("PAYLOAD_TOO_LARGE",()->new Command(id,"large",1,0,1,new byte[1<<20],List.of(new byte[1]),new byte[0]));
        rejects("CORRUPT_RECEIPT",()->new Receipt("bad",2,1,1,new byte[0],false));
        check(owned.events().size()==1,"event list alias was mutated");
        System.out.println("PASS "+checks+" Java command identity/ownership checks");
    }
}

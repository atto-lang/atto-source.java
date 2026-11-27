import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

final class ControlPlaneArenaProbe {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void deny(Runnable r){boolean denied=false;try{r.run();}catch(IllegalStateException|IllegalArgumentException e){denied=true;}check(denied);}
    static void cycle(ControlPlaneArena32 arena,int count){
        for(int i=0;i<count;i++){
            long h=arena.tryAllocate();check(h!=0);
            check(arena.readI64(h,0)==0&&arena.readRef32(h)==0);
            arena.writeI64(h,0,i);check(arena.readI64(h,0)==i);
            arena.drop(h);
        }
    }
    static void denial(ControlPlaneArena32 arena,int count){for(int i=0;i<count;i++)check(arena.tryAllocate()==0);}
    public static void main(String[] args){
        check(ControlPlaneArena32.ABI.equals("control_generation12_slot20_v1")&&ControlPlaneArena32.RECORD_BYTES==32);
        try(var a=new ControlPlaneArena32(2);var b=new ControlPlaneArena32(2)){
            long parent=a.tryAllocate(),child=a.tryAllocate(),foreign=b.tryAllocate();
            check(parent!=0&&child!=0&&foreign!=0&&(int)parent==(int)foreign);
            check(a.tryAllocate()==0);
            deny(()->b.readI64(parent,0));deny(()->a.writeRef32(parent,foreign));
            a.writeI64(parent,0,9_000_000_000L);check(a.readI64(parent,0)==9_000_000_000L);
            a.writeI64(parent,8,Long.MIN_VALUE);check(a.readI64(parent,8)==Long.MIN_VALUE);
            a.writeRef32(parent,child);check(a.readRef32(parent)==child);
            deny(()->a.writeI64(parent,16,1));
            a.drop(child);deny(()->a.readI64(child,0));deny(()->a.readRef32(parent));
            long reused=a.tryAllocate();check(reused!=0&&reused!=child&&a.readI64(reused,0)==0);
            a.writeRef32(parent,reused);check(a.readRef32(parent)==reused);
            a.drop(parent);a.drop(reused);deny(()->a.drop(reused));
        }
        try(var a=new ControlPlaneArena32(1)){
            long h=0;
            for(int i=1;i<=4095;i++){h=a.tryAllocate();check(h!=0&&((int)h>>>20)==i);a.drop(h);}
            check(a.tryAllocate()==0&&((int)h&0x80000000)!=0);
        }
        var closed=new ControlPlaneArena32(1);long stale=closed.tryAllocate();closed.close();closed.close();
        check(closed.tryAllocate()==0);deny(()->closed.readI64(stale,0));
        var base=ManagementFactory.getThreadMXBean();
        check(base instanceof ThreadMXBean);ThreadMXBean bean=(ThreadMXBean)base;
        check(bean.isThreadAllocatedMemorySupported());bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        try(var reuse=new ControlPlaneArena32(64);var full=new ControlPlaneArena32(1)){
            long held=full.tryAllocate();check(held!=0);
            cycle(reuse,65536);denial(full,65536);
            // Repeated reads warm the observation API outside measured windows.
            for(int i=0;i<128;i++)bean.getThreadAllocatedBytes(thread);
            long before=bean.getThreadAllocatedBytes(thread);
            cycle(reuse,16384);
            long after=bean.getThreadAllocatedBytes(thread);
            long deniedBefore=bean.getThreadAllocatedBytes(thread);
            denial(full,16384);
            long deniedAfter=bean.getThreadAllocatedBytes(thread);
            check(before>=0&&after>=before&&deniedBefore>=0&&deniedAfter>=deniedBefore);
            System.out.println("JAVA_CONTROL_HOST reuse=16384 quota_denials=16384 managed_reuse_delta="+(after-before)+" managed_denial_delta="+(deniedAfter-deniedBefore));
            check(after==before&&deniedAfter==deniedBefore);
            full.drop(held);
        }
        System.out.println("PASS Java FFM host control arena; no compiled Atto guest or Native Image proof");
    }
}

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/** JDK 25 host-only native control-plane experiment. Not a guest capability or scheduler API. */
final class ControlPlaneArena32 implements AutoCloseable {
    static final String ABI = "control_generation12_slot20_v1";
    static final int RECORD_BYTES = 32;
    static final int SLOT_BITS = 20;
    static final int GENERATION_BITS = 12;
    static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    static final int MAX_GENERATION = (1 << GENERATION_BITS) - 1;
    private static final int META_BYTES = 12;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final AtomicLong NEXT_ISSUER = new AtomicLong();
    private final int capacity;
    private final long issuer;
    private final Arena nativeArena;
    private final MemorySegment records, slots;
    private int freeHead, freeTail;
    private boolean closed;

    ControlPlaneArena32(int capacity) {
        if (capacity <= 0 || capacity > SLOT_MASK) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        issuer = issueDomain();
        nativeArena = Arena.ofShared();
        try {
            records = nativeArena.allocate((long)capacity * RECORD_BYTES, 8);
            slots = nativeArena.allocate(((long)capacity * META_BYTES + 7) & ~7L, 8);
            for(int i=0;i<capacity;i++){set(i,0,1);set(i,8,i+1<capacity?i+2:0);}
            freeHead=1;freeTail=capacity;
        } catch (RuntimeException | Error failure) {
            nativeArena.close();
            throw failure;
        }
    }
    private static long issueDomain() {
        for (;;) {
            long n=NEXT_ISSUER.get();
            if(n>=0xffff_ffffL) throw new IllegalStateException("issuer exhausted");
            if(NEXT_ISSUER.compareAndSet(n,n+1)) return n+1;
        }
    }
    private int get(int slot,int field) { return slots.get(I32,(long)slot*META_BYTES+field); }
    private void set(int slot,int field,int value) { slots.set(I32,(long)slot*META_BYTES+field,value); }
    private long witness(int word) { return (issuer<<32)|Integer.toUnsignedLong(word); }
    private int validate(long handle) {
        if(closed || (handle>>>32)!=issuer) throw new IllegalStateException("closed or foreign control handle");
        int word=(int)handle,low=word&SLOT_MASK,generation=word>>>SLOT_BITS;
        if(low==0||low>capacity||generation==0)throw new IllegalStateException("invalid control handle");
        int slot=low-1;
        if(get(slot,4)==0||get(slot,0)!=generation)throw new IllegalStateException("stale control handle");
        return slot;
    }
    synchronized long tryAllocate() {
        if(closed)return 0;
        int slot;
        if(freeHead==0)return 0;
        slot=freeHead-1;freeHead=get(slot,8);
        if(freeHead==0)freeTail=0;
        int generation=get(slot,0);
        if(generation==0||get(slot,4)!=0)throw new IllegalStateException("invalid free slot");
        set(slot,4,1);set(slot,8,0);
        return witness((generation<<SLOT_BITS)|(slot+1));
    }
    synchronized void drop(long handle) {
        int slot=validate(handle);long base=(long)slot*RECORD_BYTES;
        // Avoid per-release slice/facade allocation; zero all physical record bytes.
        for(int i=0;i<RECORD_BYTES;i+=8)records.set(I64,base+i,0);
        set(slot,4,0);int generation=get(slot,0);
        if(generation==MAX_GENERATION)set(slot,0,0);
        else {
            set(slot,0,generation+1);set(slot,8,0);
            if(freeTail==0)freeHead=slot+1;else set(freeTail-1,8,slot+1);
            freeTail=slot+1;
        }
    }
    private long scalarOffset(long handle,int field) {
        int slot=validate(handle);
        if(field!=0&&field!=8)throw new IllegalArgumentException("scalar field");
        return (long)slot*RECORD_BYTES+field;
    }
    synchronized void writeI64(long handle,int field,long value){records.set(I64,scalarOffset(handle,field),value);}
    synchronized long readI64(long handle,int field){return records.get(I64,scalarOffset(handle,field));}
    synchronized void writeRef32(long parent,long child){
        int slot=validate(parent);if(child!=0)validate(child);
        records.set(I32,(long)slot*RECORD_BYTES+16,(int)child);
    }
    synchronized long readRef32(long parent){
        int slot=validate(parent),word=records.get(I32,(long)slot*RECORD_BYTES+16);
        if(word==0)return 0;long child=witness(word);validate(child);return child;
    }
    @Override public synchronized void close() {
        if(closed)return;
        records.fill((byte)0);slots.fill((byte)0);
        nativeArena.close();closed=true;
    }
}

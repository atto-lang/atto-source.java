package dev.oreslang.runtime;

public final class ActorIpcCodecProfileV1Harness {
    private static void rejects(Class<? extends RuntimeException> kind, Runnable action) {
        try { action.run(); }
        catch (RuntimeException ex) { if (kind.isInstance(ex)) return; throw ex; }
        throw new AssertionError("expected rejection");
    }
    public static void main(String[] args) {
        String shape="11".repeat(32), abi="22".repeat(32);
        var p=new ActorIpcCodecProfileV1("messagepack",2,"order.v1",shape,abi);
        String hash=p.commitment();
        p.requireAdmitted(15,1,hash,abi);
        rejects(SecurityException.class,()->p.requireAdmitted(2,1,hash,abi));
        rejects(SecurityException.class,()->p.requireAdmitted(1,2,hash,abi));
        rejects(SecurityException.class,()->p.requireAdmitted(1,1,hash,shape));
        rejects(SecurityException.class,()->p.requireAdmitted(1,1,"00".repeat(32),abi));
        rejects(IllegalArgumentException.class,()->p.requireAdmitted(16,1,hash,abi));
        for(var q:new ActorIpcCodecProfileV1[]{
                new ActorIpcCodecProfileV1("protobuf",2,"order.v1",shape,abi),
                new ActorIpcCodecProfileV1("messagepack",1,"order.v1",shape,abi),
                new ActorIpcCodecProfileV1("messagepack",2,"order.v2",shape,abi),
                new ActorIpcCodecProfileV1("messagepack",2,"order.v1",abi,abi)}) {
            rejects(SecurityException.class,()->q.requireAdmitted(15,15,hash,abi));
        }
        rejects(IllegalArgumentException.class,()->new ActorIpcCodecProfileV1("CBOR",1,"s",shape,abi));
        rejects(IllegalArgumentException.class,()->new ActorIpcCodecProfileV1("cbor",0,"s",shape,abi));
        rejects(IllegalArgumentException.class,()->new ActorIpcCodecProfileV1("cbor",1,"s\nx",shape,abi));
        rejects(IllegalArgumentException.class,()->new ActorIpcCodecProfileV1("cbor",1,"s","GG".repeat(32),abi));
        System.out.println("ACTOR_IPC_CODEC_PROFILE_HOST_PASS commitment="+hash);
    }
}

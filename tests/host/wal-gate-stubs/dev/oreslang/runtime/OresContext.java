package dev.oreslang.runtime;
final class OresContext {
    private final ActorRuntime runtime;
    boolean writeAllowed=true;
    OresContext(ActorRuntime runtime){this.runtime=runtime;}
    ActorRuntime actors(){return runtime;}
    void requirePermission(RuntimePermissions.Permission permission,String resource,String api){
        if(!writeAllowed || permission!=RuntimePermissions.Permission.WRITE)
            throw new SecurityException("denied write");
    }
}

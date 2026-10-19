module dev.oreslang {
    requires java.management;
    // PostgreSQL durable-host adapter compiles against SQL without imposing
    // a mandatory runtime dependency on the freestanding actor substrate.
    requires static java.sql;
    requires java.net.http;
    requires jdk.httpserver;
    requires java.base;
    requires java.compiler;
    requires java.logging;
    requires org.graalvm.polyglot;
    requires org.graalvm.nativeimage;
    requires org.graalvm.truffle;
    requires org.tomlj;

    exports dev.oreslang.launcher;
    exports dev.oreslang.compiler;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.oreslang.OresLanguageProvider;
}

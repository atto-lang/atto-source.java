module dev.atto {
    requires java.management;
    requires java.net.http;
    requires jdk.httpserver;
    requires java.base;
    requires java.compiler;
    requires java.logging;
    requires org.graalvm.polyglot;
    requires org.graalvm.nativeimage;
    requires org.graalvm.truffle;
    requires org.tomlj;

    exports dev.atto.launcher;
    exports dev.atto.compiler;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.atto.OresLanguageProvider;
}

module dev.oreslang {
    requires java.management;
    requires java.net.http;
    requires java.sql;
    requires jdk.httpserver;
    requires java.base;
    requires java.compiler;
    requires java.logging;
    requires java.sql;
    requires jdk.management;
    requires org.graalvm.polyglot;
    requires org.graalvm.nativeimage;
    requires org.graalvm.truffle;
    requires org.tomlj;
    requires org.xerial.sqlitejdbc;
    requires com.fasterxml.jackson.core;

    exports dev.oreslang.launcher;
    exports dev.oreslang.compiler;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.oreslang.OresLanguageProvider;
}

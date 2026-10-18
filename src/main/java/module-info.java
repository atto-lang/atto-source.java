module dev.oreslang {
    requires java.management;
    requires java.net.http;
    requires jdk.httpserver; // Test fixtures; production HTTP transport uses NIO.
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.transport;
    requires io.netty.buffer;
    requires io.netty.common;
    requires io.netty.handler;
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

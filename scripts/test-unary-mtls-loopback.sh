#!/bin/sh
# Disposable, JDK-only TLS 1.3 loopback handshake proof. No production keys.
set -eu
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    keytool="$JAVA_HOME/bin/keytool"
else
    keytool=$(command -v keytool)
fi

"$keytool" -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 2 \
    -storetype PKCS12 -keystore "$tmp/server.p12" -storepass changeit \
    -keypass changeit -dname 'CN=localhost' \
    -ext 'SAN=dns:localhost,ip:127.0.0.1' -ext 'EKU=serverAuth' \
    -noprompt >/dev/null 2>&1
"$keytool" -genkeypair -alias client -keyalg RSA -keysize 2048 -validity 2 \
    -storetype PKCS12 -keystore "$tmp/client.p12" -storepass changeit \
    -keypass changeit -dname 'CN=oreslang-test-client' \
    -ext 'EKU=clientAuth' -noprompt >/dev/null 2>&1
"$keytool" -exportcert -alias server -keystore "$tmp/server.p12" \
    -storepass changeit -file "$tmp/server.cer" >/dev/null 2>&1
"$keytool" -exportcert -alias client -keystore "$tmp/client.p12" \
    -storepass changeit -file "$tmp/client.cer" >/dev/null 2>&1
"$keytool" -importcert -alias server -file "$tmp/server.cer" \
    -keystore "$tmp/client-trust.p12" -storetype PKCS12 \
    -storepass changeit -noprompt >/dev/null 2>&1
"$keytool" -importcert -alias client -file "$tmp/client.cer" \
    -keystore "$tmp/server-trust.p12" -storetype PKCS12 \
    -storepass changeit -noprompt >/dev/null 2>&1

mkdir -p "$tmp/classes"
javac --release 21 -Xlint:all -Werror -d "$tmp/classes" \
    src/main/java/dev/oreslang/runtime/OresBytes.java \
    src/main/java/dev/oreslang/runtime/UnaryTransportFrame.java \
    src/main/java/dev/oreslang/runtime/UnaryInboundRequestRegistry.java \
    src/main/java/dev/oreslang/runtime/UnaryTcpSessionAdapter.java \
    src/main/java/dev/oreslang/runtime/UnaryTcpMutualTlsAuthenticator.java \
    src/main/java/dev/oreslang/runtime/UnaryTcpTlsListener.java \
    src/test/java/dev/oreslang/runtime/UnaryTcpMutualTlsLoopbackCheck.java \
    src/test/java/dev/oreslang/runtime/UnaryTcpTlsListenerCheck.java
java -cp "$tmp/classes" dev.oreslang.runtime.UnaryTcpMutualTlsLoopbackCheck "$tmp"
java -cp "$tmp/classes" dev.oreslang.runtime.UnaryTcpTlsListenerCheck "$tmp"

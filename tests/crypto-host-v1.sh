#!/bin/sh
# Source-only trusted JDK provider smoke: NOT Atto guest/AOT conformance.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/classes"
cat > "$TMP/CryptoSmoke.java" <<'JAVA'
import dev.oreslang.runtime.AeadPrimitives;
import dev.oreslang.runtime.CryptoPrimitives;
import dev.oreslang.runtime.HkdfSha256;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Arrays;
public final class CryptoSmoke {
  static void require(boolean yes, String reason) {
    if (!yes) throw new AssertionError(reason);
  }
  static String hex(byte[] data) { return HexFormat.of().formatHex(data); }
  public static void main(String[] unused) {
    require(hex(CryptoPrimitives.sha256("abc".getBytes())).equals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256");
    require(hex(CryptoPrimitives.pbkdf2HmacSha256("password".getBytes(),
      "salt".getBytes(), 1, 64)).equals(
      "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
      + "4dbf3a2f3dad3377264bb7b8e8330d4efc7451418617dabef683735361cdc18c"), "PBKDF2 multi-block");
    byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte) 0x0b);
    byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
    byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
    require(hex(HkdfSha256.derive(ikm, salt, info, 42)).equals(
      "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
      + "34007208d5b887185865"), "RFC5869 HKDF");
    for (int algorithm : new int[]{1,2}) {
      var nonces = new HashSet<String>();
      try (var key = AeadPrimitives.generateKey(algorithm);
           var wrong = AeadPrimitives.generateKey(algorithm)) {
        for (int i = 0; i < 128; ++i) {
          var sealed = key.seal(new byte[]{1,2}, new byte[]{10,20,30});
          require(nonces.add(hex(sealed.nonce())), "nonce reused");
          require(Arrays.equals(key.open(sealed.nonce(), new byte[]{1,2},
            sealed.ciphertext()), new byte[]{10,20,30}), "AEAD roundtrip");
          var altered = sealed.ciphertext(); altered[0] ^= 1;
          boolean denied = false;
          try { key.open(sealed.nonce(), new byte[]{1,2}, altered); }
          catch (AeadPrimitives.AuthenticationException e) { denied = true; }
          require(denied, "tag tampering accepted");
          denied = false;
          try { wrong.open(sealed.nonce(), new byte[]{1,2}, sealed.ciphertext()); }
          catch (AeadPrimitives.AuthenticationException e) { denied = true; }
          require(denied, "wrong key accepted");
        }
        key.close();
        boolean denied = false;
        try { key.seal(new byte[0], new byte[0]); }
        catch (IllegalStateException e) { denied = true; }
        require(denied, "closed key reused");
      }
    }
    System.out.println("ATTO_CRYPTO_JAVA_HOST_V1_PASS");
  }
}
JAVA
javac --release 21 -d "$TMP/classes" \
  "$ROOT/src/main/java/dev/oreslang/runtime/AeadPrimitives.java" \
  "$ROOT/src/main/java/dev/oreslang/runtime/CryptoPrimitives.java" \
  "$ROOT/src/main/java/dev/oreslang/runtime/HkdfSha256.java" \
  "$TMP/CryptoSmoke.java"
java -cp "$TMP/classes" CryptoSmoke

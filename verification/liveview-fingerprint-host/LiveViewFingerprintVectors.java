// Standalone JVM host implementation of LiveView envelope serialization/digest.
// Does not implement, exercise or certify an Oreslang guest compiler.
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

public final class LiveViewFingerprintVectors {
  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i+1)))
          throw new IllegalArgumentException("lone high surrogate");
        b.append(c).append(s.charAt(++i));
      } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("lone low surrogate");
      else switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 32) b.append(String.format("\\u%04x", (int)c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }
  private static String digest(String user, String name, long revision, String canonicalPayload) throws Exception {
    if (revision < 0 || revision > 9007199254740991L) throw new IllegalArgumentException("revision out of range");
    String json = "["+quote(user)+","+quote(name)+","+revision+","+quote(canonicalPayload)+"]";
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
  }
  private static String decode(String base64) throws Exception {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(Base64.getDecoder().decode(base64))).toString();
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("usage: java LiveViewFingerprintVectors vectors.tsv");
    int count=0;
    for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
      if (line.startsWith("#") || line.isBlank()) continue;
      String[] cols=line.split("\t",-1);
      if(cols.length != 6) throw new IllegalArgumentException("malformed vector: "+line);
      String decodeUser = decode(cols[1]);
      String decodeName = decode(cols[2]);
      String payload = decode(cols[4]);
      String actual = digest(decodeUser,decodeName,Long.parseLong(cols[3]),payload);
      if(!actual.equals(cols[5])) throw new AssertionError(cols[0]+": "+actual+" != "+cols[5]);
      count++;
    }
    if(count != 8) throw new AssertionError("expected 8 vectors, got "+count);
    // Reject malformed scalar inputs and revision overflow.
    for(String s : new String[]{"\ud800","\udc00"}) {
      try {quote(s);throw new AssertionError("lone surrogate accepted");}
      catch (IllegalArgumentException expected) { /* positive safety check */ }
    }
    try {digest("alice","event",9007199254740992L,"{}");throw new AssertionError("unsafe revision");}
    catch (IllegalArgumentException expected) { /* positive safety check */ }
    try {decode("wIA=");throw new AssertionError("malformed UTF-8 accepted");}
    catch (java.nio.charset.CharacterCodingException expected) { /* positive safety check */ }
    System.out.println("JVM HOST LiveView fingerprint: "+count+"/8 vectors passed; 4 negative checks passed");
  }
}

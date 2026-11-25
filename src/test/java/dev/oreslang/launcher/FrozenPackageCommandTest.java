package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

final class FrozenPackageCommandTest {
    @TempDir Path root;
    private static final String NAME="@org/project/package",VERSION="1.0.0",PATH="src/network.atto";
    private static final String SOURCE="use namespace network; pub fnc answer(int x):int { return x + 2; }";
    private static final String MANIFEST="[package]\nname = \""+NAME+"\"\nversion = \""+VERSION+"\"\n[namespaces]\nnetwork = \""+PATH+"\"\n";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String sha(byte[] data)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
    }
    private Path archive(String file,byte[] lock,String source,String extra)throws Exception {
        Path path=root.resolve(file);
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip,".atto.pkg.toml",bytes(MANIFEST));
            entry(zip,".atto.pkg.lock",lock);
            entry(zip,PATH,bytes(source));
            if(extra!=null) entry(zip,extra,bytes("pub fnc unexpected():int{return 0;}"));
        }
        return path;
    }
    private String[] command(String verb,Path guest,Path zip,String digest) {
        return new String[]{verb,guest.toString(),"--package",zip.toString(),NAME,VERSION,digest};
    }
    @Test void actualLauncherChecksAndRunsFrozenArchiveAndRejectsTampering()throws Exception {
        Path guest=root.resolve("main.atto");
        Files.writeString(guest,"import pkg '"+NAME+"' as p; pub fnc main()->int{return p.network.answer(40);}");
        byte[] lock=bytes("[lock]\nmanifest_sha256 = \""+sha(bytes(MANIFEST))+"\"\n[files]\n\""+PATH+"\" = \""+sha(bytes(SOURCE))+"\"\n");
        String pin=sha(lock); Path good=archive("good.zip",lock,SOURCE,null);
        assertEquals(0,OresMain.exitStatusForArgs(command("check-frozen",guest,good,pin)));
        assertEquals(42,OresMain.exitStatusForArgs(command("run-frozen",guest,good,pin)));
        assertNotEquals(0,OresMain.exitStatusForArgs(command("check-frozen",guest,good,"0".repeat(64))));
        Path changed=archive("tampered.zip",lock,SOURCE.replace("2;","3;"),null);
        assertNotEquals(0,OresMain.exitStatusForArgs(command("check-frozen",guest,changed,pin)));
        for(String name:List.of("../evil.atto","extra.atto","src\\evil.atto")) {
            Path bad=archive("bad.zip",lock,SOURCE,name);
            assertNotEquals(0,OresMain.exitStatusForArgs(command("check-frozen",guest,bad,pin)));
        }
        assertNotEquals(0,OresMain.exitStatusForArgs(new String[]{"check-frozen",guest.toString()}));
        var grant=Arrays.copyOf(command("check-frozen",guest,good,pin),8); grant[7]="--allow-all";
        assertNotEquals(0,OresMain.exitStatusForArgs(grant));
        Files.writeString(guest,"import pkg '"+NAME+"' as p; pub fnc main()->int{return p.network.missing(40);}");
        assertNotEquals(0,OresMain.exitStatusForArgs(command("check-frozen",guest,good,pin)));
    }
}

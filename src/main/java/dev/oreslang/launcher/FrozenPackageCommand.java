package dev.oreslang.launcher;

import dev.oreslang.imports.FrozenPackageArchive;
import dev.oreslang.imports.FrozenImportSnapshot;
import dev.oreslang.imports.FrozenScalarImportCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.NativeBuildMode;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Path;
import java.util.*;

/** Explicit opt-in CLI lane. Never confers ambient authority or claims full guest AOT. */
final class FrozenPackageCommand {
    private FrozenPackageCommand() {}
    static int run(String[] args) throws Exception {
        boolean check=args[0].equals("check-frozen");
        if(args.length<7||(args.length-2)%5!=0||(args.length-2)/5>16)
            throw new IllegalArgumentException("usage: check-frozen|run-frozen INPUT.atto --package ZIP NAME VERSION TRUSTED_LOCK_SHA256 [--package ...]");
        var packages=new ArrayList<FrozenImportSnapshot>();
        for(int i=2;i<args.length;i+=5) {
            if(!args[i].equals("--package")) throw new IllegalArgumentException("E_IMPORT_PATH: expected --package");
            packages.add(FrozenPackageArchive.read(Path.of(args[i+1]),args[i+2],args[i+3],args[i+4]));
        }
        if(!args[1].endsWith(".atto")) throw new IllegalArgumentException("E_IMPORT_PATH: frozen consumer must be .atto");
        byte[] bytes=FrozenPackageArchive.readBounded(Path.of(args[1]),65536);
        String consumer=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        var guest=FrozenScalarImportCompiler.compile(consumer,packages);
        if(check) {
            System.out.println("FROZEN_CHECK|scope=bounded-scalar|packages="+packages.size());
            return 0;
        }
        var profile=ExecutionProfile.parse("aot","server");
        NativeBuildMode.requireCompatible(profile);
        return guest.run(profile);
    }
}

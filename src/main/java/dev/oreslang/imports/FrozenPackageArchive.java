package dev.oreslang.imports;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit host-supplied transport, not package discovery. Nothing is
 * extracted or written. Trust comes from the independent expected lock pin,
 * never from the ZIP, its metadata, or a digest calculated from that ZIP. */
public final class FrozenPackageArchive {
    private static final int MAX_ARCHIVE = 8 * 1024 * 1024, MAX_TOTAL = 8 * 1024 * 1024;
    private FrozenPackageArchive() {}
    public static byte[] readBounded(Path path,int limit) throws IOException {
        if(limit<1||limit>MAX_ARCHIVE) throw new IllegalArgumentException("E_SOURCE_LIMIT: invalid byte budget");
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("E_IMPORT_PATH: expected explicit regular file");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=in.readNBytes(limit+1);
            if(bytes.length>limit) throw new IOException("E_SOURCE_LIMIT: input exceeds byte budget");
            return bytes;
        }
    }
    public static FrozenImportSnapshot read(Path archive,String name,String version,String trustedLock) throws IOException {
        byte[] zip=readBounded(archive,MAX_ARCHIVE);
        var directory=FrozenZipDirectory.inspect(zip);
        byte[] manifest=null,lock=null;
        var sources=new TreeMap<String,byte[]>();
        var names=new HashSet<String>();
        int total=0;
        try(var in=new ZipInputStream(new ByteArrayInputStream(zip),java.nio.charset.StandardCharsets.UTF_8)) {
            for(ZipEntry entry;(entry=in.getNextEntry())!=null;) {
                String key=entry.getName();
                if(names.size()>=66||!names.add(key)||entry.isDirectory())
                    throw new IOException("E_IMPORT_PATH: duplicate, directory or excessive archive entry");
                var indexed=directory.get(key);
                if(indexed==null) throw new IOException("E_IMPORT_PATH: unindexed local ZIP entry");
                int limit;
                if(key.equals(".atto.pkg.toml")) limit=8192;
                else if(key.equals(".atto.pkg.lock")) limit=32768;
                else if(key.matches("(?:[A-Za-z0-9][A-Za-z0-9_-]{0,63}/)*[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.atto")&&key.length()<=256) limit=1048576;
                else throw new IOException("E_IMPORT_PATH: invalid archive entry name");
                if(indexed.size()>limit||indexed.size()>MAX_TOTAL-total)
                    throw new IOException("E_SOURCE_LIMIT: expanded archive budget exceeded");
                byte[] bytes=in.readNBytes(Math.min(limit,MAX_TOTAL-total)+1);
                if(bytes.length>limit||bytes.length>MAX_TOTAL-total)
                    throw new IOException("E_SOURCE_LIMIT: expanded archive budget exceeded");
                total+=bytes.length;
                if(key.equals(".atto.pkg.toml")) manifest=bytes;
                else if(key.equals(".atto.pkg.lock")) lock=bytes;
                else sources.put(key,bytes);
                in.closeEntry();
                if(entry.getCrc()!=indexed.crc()||entry.getCompressedSize()!=indexed.compressed()||
                    entry.getSize()!=indexed.size()||bytes.length!=indexed.size())
                    throw new IOException("E_IMPORT_PATH: local/central ZIP content mismatch");
            }
        }
        if(!names.equals(directory.keySet())) throw new IOException("E_IMPORT_PATH: incomplete ZIP inventory");
        if(manifest==null||lock==null) throw new IOException("E_LOCK: missing manifest or lock entry");
        return FrozenImportSnapshot.verify(name,version,trustedLock,manifest,lock,sources);
    }
}

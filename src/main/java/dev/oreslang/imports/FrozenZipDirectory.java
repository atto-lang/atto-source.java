package dev.oreslang.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict bounded ZIP32 envelope: one disk, no archive comment, contiguous
 * local records, and exactly matching central/local identities. No extraction. */
final class FrozenZipDirectory {
    record Entry(long crc,long compressed,long size) {}
    private record Span(int start,int end) {}
    private static final long LOCAL=0x04034b50L, CENTRAL=0x02014b50L, END=0x06054b50L, DESCRIPTOR=0x08074b50L;
    private FrozenZipDirectory() {}
    private static IOException bad() {return new IOException("E_IMPORT_PATH: invalid frozen ZIP directory");}
    private static void range(int at,int length,int end)throws IOException {
        if(at<0||length<0||(long)at+length>end) throw bad();
    }
    private static int u16(byte[] b,int at) {return (b[at]&255)|((b[at+1]&255)<<8);}
    private static long u32(byte[] b,int at) {return (long)u16(b,at)|((long)u16(b,at+2)<<16);}
    private static boolean fields(byte[] b,int at,Entry entry) {
        return u32(b,at)==entry.crc()&&u32(b,at+4)==entry.compressed()&&u32(b,at+8)==entry.size();
    }
    static Map<String,Entry> inspect(byte[] zip)throws IOException {
        int end=zip.length-22;
        range(end,22,zip.length);
        if(u32(zip,end)!=END||u16(zip,end+4)!=0||u16(zip,end+6)!=0||u16(zip,end+20)!=0) throw bad();
        int count=u16(zip,end+10);
        long centralSize=u32(zip,end+12),centralOffset=u32(zip,end+16);
        if(count<1||count>66||u16(zip,end+8)!=count||centralOffset+centralSize!=end) throw bad();
        int central=(int)centralOffset,at=central;
        var entries=new LinkedHashMap<String,Entry>();
        var spans=new ArrayList<Span>();
        for(int i=0;i<count;i++) {
            range(at,46,end);
            if(u32(zip,at)!=CENTRAL||u16(zip,at+34)!=0) throw bad();
            int flags=u16(zip,at+8),method=u16(zip,at+10),n=u16(zip,at+28),extra=u16(zip,at+30),comment=u16(zip,at+32);
            if((flags&~0x080e)!=0||(method!=0&&method!=8)||(method==0&&(flags&14)!=0)||n<1||n>256) throw bad();
            range(at+46,n+extra+comment,end);
            for(int k=at+46;k<at+46+n;k++) if((zip[k]&255)<32||(zip[k]&255)>126) throw bad();
            String name=new String(zip,at+46,n,StandardCharsets.US_ASCII);
            var entry=new Entry(u32(zip,at+16),u32(zip,at+20),u32(zip,at+24));
            long localOffset=u32(zip,at+42);
            if(localOffset>central||entry.compressed()>central||entry.size()>0xffffffffL||entries.putIfAbsent(name,entry)!=null) throw bad();
            int local=(int)localOffset;
            range(local,30,central);
            if(u32(zip,local)!=LOCAL||u16(zip,local+6)!=flags||u16(zip,local+8)!=method||u16(zip,local+26)!=n) throw bad();
            int localExtra=u16(zip,local+28);
            range(local+30,n+localExtra,central);
            if(!Arrays.equals(zip,at+46,at+46+n,zip,local+30,local+30+n)) throw bad();
            int data=local+30+n+localExtra;
            range(data,(int)entry.compressed(),central);
            int after=data+(int)entry.compressed();
            if((flags&8)==0) {
                if(!fields(zip,local+14,entry)) throw bad();
            } else {
                // Both descriptor encodings are admitted; signature first if present.
                range(after,12,central);
                if(u32(zip,after)==DESCRIPTOR) {
                    range(after,16,central);
                    if(!fields(zip,after+4,entry)) throw bad();
                    after+=16;
                } else {
                    if(!fields(zip,after,entry)) throw bad();
                    after+=12;
                }
                for(int field=0;field<3;field++) {
                    long value=u32(zip,local+14+4*field);
                    if(value!=0&&value!=u32(zip,at+16+4*field)) throw bad();
                }
            }
            spans.add(new Span(local,after));
            at+=46+n+extra+comment;
        }
        if(at!=end) throw bad();
        spans.sort(Comparator.comparingInt(Span::start));
        int cursor=0;
        for(Span span:spans) {
            if(span.start()!=cursor||span.end()<=cursor) throw bad();
            cursor=span.end();
        }
        if(cursor!=central) throw bad();
        return Collections.unmodifiableMap(entries);
    }
}

package dev.oreslang.compiler;

import java.io.*;

/** Independent decoder for the closed checked-i64 classfile format.
 * Reject all unrecognized constant-pool entries, attributes, instructions,
 * external calls and trailing data. This is not a general JVM verifier. */
final class CheckedI64ClassfileAudit {
    private static IllegalArgumentException deny(String detail){
        return new IllegalArgumentException("E_I64_CLASSFILE_AUDIT: "+detail);
    }
    private static void need(boolean ok,String detail){if(!ok)throw deny(detail);}
    private static void u1(DataInputStream in,int expected)throws IOException{need(in.readUnsignedByte()==expected,"unexpected byte");}
    private static void u2(DataInputStream in,int expected)throws IOException{need(in.readUnsignedShort()==expected,"unexpected short");}
    private static void utf(DataInputStream in,String expected)throws IOException{u1(in,1);need(in.readUTF().equals(expected),"unexpected UTF constant");}
    private static void one(DataInputStream in,int tag,int a)throws IOException{u1(in,tag);u2(in,a);}
    private static void two(DataInputStream in,int tag,int a,int b)throws IOException{u1(in,tag);u2(in,a);u2(in,b);}
    static void audit(byte[] bytes,int arity,long maxSteps){
        need(bytes!=null&&bytes.length<=8192&&arity>=0&&arity<=4&&maxSteps>=1,"input budget");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            need(in.readInt()==0xcafebabe,"magic");u2(in,0);u2(in,52);
            int cp=in.readUnsignedShort();
            need(cp>=24&&cp<=536&&(cp-24)%2==0,"constant pool budget");
            utf(in,"AttoCheckedI64Provider");one(in,7,1);utf(in,"java/lang/Object");one(in,7,3);
            utf(in,"java/lang/Math");one(in,7,5);utf(in,"Code");utf(in,"invoke");
            utf(in,"("+"J".repeat(arity)+")J");
            utf(in,"addExact");utf(in,"subtractExact");utf(in,"multiplyExact");utf(in,"negateExact");
            utf(in,"(JJ)J");utf(in,"(J)J");
            for(int i=0;i<4;i++)two(in,12,10+i,i==3?15:14);
            for(int i=0;i<4;i++)two(in,10,6,16+i);
            for(int i=24;i<cp;i+=2){u1(in,5);in.readLong();}
            u2(in,0x0031);u2(in,2);u2(in,4);
            u2(in,0);u2(in,0);u2(in,1);
            u2(in,0x0009);u2(in,8);u2(in,9);u2(in,1);u2(in,7);
            int attribute=in.readInt();int stackLimit=in.readUnsignedShort();
            need(stackLimit==66,"stack limit");u2(in,2*arity);
            int size=in.readInt();
            need(size>=2&&size<=769&&attribute==12+size,"code size");
            byte[] code=in.readNBytes(size);need(code.length==size,"truncated code");
            u2(in,0);u2(in,0);u2(in,0);need(in.read()==-1,"trailing bytes");
            int height=0,steps=0;
            try(var instructions=new DataInputStream(new ByteArrayInputStream(code))){
                while(instructions.available()>0){
                    int op=instructions.readUnsignedByte();
                    if(op==0xad){
                        need(height==1&&steps>=1&&instructions.available()==0,"return stack or trailing instruction");
                        return;
                    }
                    need(++steps<=256&&steps<=maxSteps,"instruction budget");
                    switch(op){
                        case 0x14 -> {int idx=instructions.readUnsignedShort();need(idx>=24&&idx<cp&&(idx-24)%2==0,"long constant index");height++;}
                        case 0x16 -> {int local=instructions.readUnsignedByte();need(local<2*arity&&local%2==0,"long parameter index");height++;}
                        case 0xb8 -> {
                            int target=instructions.readUnsignedShort();
                            need(target>=20&&target<=23,"external call");
                            int args=target==23?1:2;
                            need(height>=args,"operand stack underflow");height-=args-1;
                        }
                        default -> throw deny("forbidden opcode "+op);
                    }
                    need(height*2<=stackLimit,"operand stack overflow");
                }
            }
            throw deny("missing return");
        }catch(IOException ex){throw deny("truncated or malformed classfile");}
    }
}

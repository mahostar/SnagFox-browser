package app.vidloader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small bounded protobuf codec for the recovered SABR messages. No generated SDK dependency. */
final class YoutubeWire {
  static final class Writer {
    final ByteArrayOutputStream output=new ByteArrayOutputStream();
    Writer number(int tag,long value){varint(output,((long)tag)<<3);varint(output,value);return this;}
    Writer bytes(int tag,byte[] value){if(value!=null){varint(output,(((long)tag)<<3)|2);varint(output,value.length);output.write(value,0,value.length);}return this;}
    Writer text(int tag,String value){return value==null?this:bytes(tag,value.getBytes(StandardCharsets.UTF_8));}
    Writer floating(int tag,float value){varint(output,(((long)tag)<<3)|5);int bits=Float.floatToIntBits(value);for(int i=0;i<4;i++)output.write((bits>>>(8*i))&255);return this;}
    byte[] build(){return output.toByteArray();}
  }
  static final class Field {
    final int tag,wire;final long number;final byte[] bytes;
    Field(int tag,int wire,long number,byte[] bytes){this.tag=tag;this.wire=wire;this.number=number;this.bytes=bytes;}
    String text(){return bytes==null?"":new String(bytes,StandardCharsets.UTF_8);}
  }
  static List<Field> parse(byte[] bytes)throws IOException {
    if(bytes.length>4*1024*1024)throw new IOException("SABR protobuf message exceeds bound");
    ByteArrayInputStream input=new ByteArrayInputStream(bytes);List<Field> fields=new ArrayList<>();
    while(input.available()>0){long key=readVarint(input);int tag=(int)(key>>>3),wire=(int)(key&7);if(tag<1)throw new IOException("Invalid SABR protobuf tag");
      switch(wire){
        case 0:fields.add(new Field(tag,wire,readVarint(input),null));break;
        case 1:case 5:{int length=wire==1?8:4;byte[] data=YoutubeIO.readExactly(input,length);if(data.length!=length)throw new EOFException("Truncated SABR fixed field");long value=0;for(int i=0;i<length;i++)value|=(long)(data[i]&255)<<(i*8);fields.add(new Field(tag,wire,value,data));break;}
        case 2:{long length=readVarint(input);if(length<0||length>input.available())throw new EOFException("Truncated SABR message");fields.add(new Field(tag,wire,0,YoutubeIO.readExactly(input,(int)length)));break;}
        default:throw new IOException("Unsupported SABR protobuf wire type");
      }
      if(fields.size()>32768)throw new IOException("Too many SABR protobuf fields");
    }return fields;
  }
  static Field field(byte[] message,int tag)throws IOException {for(Field field:parse(message))if(field.tag==tag)return field;return null;}
  static long number(byte[] message,int tag,long fallback)throws IOException {Field field=field(message,tag);return field==null?fallback:field.number;}
  static byte[] bytes(byte[] message,int tag)throws IOException {Field field=field(message,tag);return field==null?null:field.bytes;}
  static String text(byte[] message,int tag)throws IOException {Field field=field(message,tag);return field==null?"":field.text();}
  static long readVarint(InputStream input)throws IOException {
    long value=0;for(int shift=0;shift<64;shift+=7){int b=input.read();if(b<0)throw new EOFException("Truncated SABR varint");if(shift==63&&(b&254)!=0)throw new IOException("SABR varint overflow");value|=(long)(b&127)<<shift;if((b&128)==0)return value;}throw new IOException("SABR varint overflow");
  }
  private static void varint(OutputStream output,long value){try{while((value&~127L)!=0){output.write(((int)value&127)|128);value>>>=7;}output.write((int)value);}catch(IOException impossible){throw new AssertionError(impossible);}}
}

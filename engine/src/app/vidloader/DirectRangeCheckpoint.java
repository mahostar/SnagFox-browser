package app.vidloader;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

/** Durable, validator-bound offsets for interrupted direct-file connections. */
final class DirectRangeCheckpoint {
  private DirectRangeCheckpoint() {}
  static File state(File part) { return new File(part+".range-state"); }
  static String digest(File file) throws Exception {
    return hex(hashPrefix(file,file.length()));
  }
  static MessageDigest hashPrefix(File file,long bytes) throws Exception {
    MessageDigest hash=MessageDigest.getInstance("SHA-256");
    try(InputStream in=new FileInputStream(file)) {
      byte[] buffer=new byte[65536];int count;
      while(bytes>0){count=in.read(buffer,0,(int)Math.min(buffer.length,bytes));if(count<0)throw new EOFException("Incomplete committed prefix");hash.update(buffer,0,count);bytes-=count;}
    }
    return hash;
  }
  static String hex(MessageDigest hash) {
    StringBuilder value=new StringBuilder();
    for(byte b:hash.digest())value.append(String.format("%02x",b));
    return value.toString();
  }
  static long restore(File part,String url,String validator,long offset,long length) throws Exception {
    if(validator.isEmpty() || validator.startsWith("W/"))return 0;
    File state=state(part);
    if(!part.isFile() || !state.isFile() || state.length()>16384)return 0;
    try(DataInputStream in=new DataInputStream(new FileInputStream(state))) {
      if(in.readInt()!=0x56445231 || !in.readUTF().equals(url) || !in.readUTF().equals(validator)
          || in.readLong()!=offset || in.readLong()!=length)return 0;
      long bytes=in.readLong();String sha=in.readUTF();
      if(bytes<=0 || bytes>length || bytes>part.length() || !sha.equals(hex(hashPrefix(part,bytes))))return 0;
      // A killed writer may leave data after the last durable commit. Keep only
      // the verified prefix; never advertise unsynced/uncommitted progress.
      if(part.length()>bytes)try(RandomAccessFile file=new RandomAccessFile(part,"rw")){file.setLength(bytes);file.getFD().sync();}
      return bytes;
    } catch(IOException invalid) {return 0;}
  }
  static void save(File part,String url,String validator,long offset,long length) throws Exception {
    if(!part.isFile() || part.length()==0 || part.length()>length)return;
    save(part,url,validator,offset,length,part.length(),digest(part));
  }
  static void save(File part,String url,String validator,long offset,long length,long bytes,String sha) throws Exception {
    if(bytes<=0 || bytes>length || part.length()!=bytes || !sha.matches("[0-9a-f]{64}"))throw new IOException("Invalid range checkpoint");
    File temporary=new File(state(part)+".new");
    try(FileOutputStream stream=new FileOutputStream(temporary);DataOutputStream out=new DataOutputStream(stream)) {
      out.writeInt(0x56445231);out.writeUTF(url);out.writeUTF(validator);
      out.writeLong(offset);out.writeLong(length);out.writeLong(bytes);out.writeUTF(sha);
      out.flush();stream.getFD().sync();
    }
    try{Files.move(temporary.toPath(),state(part).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),state(part).toPath(),StandardCopyOption.REPLACE_EXISTING);}
  }
}

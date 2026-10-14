package app.vidloader;

import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import org.json.JSONObject;

/** YouTube's bounded HTTP-chunk contract, independent of the generic downloader. */
final class YoutubeChunks {
  interface Transport {
    HttpURLConnection open(String url,Map<String,String> headers)throws IOException;
    void close(HttpURLConnection connection);
    void check()throws IOException;
    void position(long bytes,long total);
    void received(int bytes);
  }
  static boolean applies(String url){try{String host=Uri.parse(url).getHost();return host!=null&&(host.equals("googlevideo.com")||host.endsWith(".googlevideo.com"));}catch(RuntimeException invalid){return false;}}
  static String identity(String url){
    Uri uri=Uri.parse(url);StringBuilder key=new StringBuilder();
    for(String field:new String[]{"id","itag","clen","lmt"}){String value=uri.getQueryParameter(field);if(value==null||value.isEmpty())return YoutubeResolver.fingerprint(url);key.append(field).append('=').append(value).append('&');}
    return YoutubeResolver.fingerprint(key.toString());
  }
  static void rebind(File file,String refreshed)throws Exception {
    File checkpoint=new File(file+".youtube.json");if(!checkpoint.isFile())return;
    JSONObject saved=new JSONObject(new String(Files.readAllBytes(checkpoint.toPath()),StandardCharsets.UTF_8));
    long length=saved.optLong("length",-1);if(length<0||length>file.length()||!saved.optString("sha256").equals(digest(file,length)))throw new IOException("Saved media checkpoint changed during renewal");
    String declared=Uri.parse(refreshed).getQueryParameter("clen");if(declared!=null&&saved.optLong("total",-1)>0&&Long.parseLong(declared)!=saved.optLong("total"))throw new IOException("Renewed media length differs from saved representation");
    saved.put("identity",identity(refreshed));File temporary=new File(checkpoint+".new");try(FileOutputStream output=new FileOutputStream(temporary)){output.write(saved.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}Files.move(temporary.toPath(),checkpoint.toPath(),StandardCopyOption.REPLACE_EXISTING);
  }
  /** SnapTube i79.o: compare a nontrivial saved block before joining renewed bytes. */
  static boolean sameSavedSource(File saved,String url,Map<String,String> headers,Transport transport)throws Exception {
    if(!saved.isFile()||saved.length()<4096)return false;
    try(RandomAccessFile local=new RandomAccessFile(saved,"r")){
      for(int i=1;i<=2;i++){
        long position=saved.length()-4096L*i;if(position<0)break;byte[] expected=new byte[4096];local.seek(position);local.readFully(expected);
        boolean[] values=new boolean[256];int distinct=0;for(byte value:expected)if(!values[value&255]){values[value&255]=true;distinct++;}
        if(distinct<8)continue;
        Map<String,String> request=new LinkedHashMap<>(headers);request.put("Range","bytes="+position+"-"+(position+4095));
        HttpURLConnection connection=transport.open(url,request);
        try{
          if(connection.getResponseCode()!=206)return false;
          String range=Optional.ofNullable(connection.getHeaderField("Content-Range")).orElse("");if(!range.startsWith("bytes "+position+"-"+(position+4095)+"/"))return false;
          byte[] actual=new byte[4096];try(DataInputStream input=new DataInputStream(connection.getInputStream())){transport.check();input.readFully(actual);}
          return Arrays.equals(expected,actual);
        }finally{transport.close(connection);}
      }
    }return false;
  }
  static File fetch(String url,Map<String,String> original,File file,long requestedChunk,Transport transport)throws Exception {
    long chunk=Math.max(256*1024,Math.min(10*1024*1024,requestedChunk>0?requestedChunk:9*1024*1024));
    String identity=identity(url);File checkpoint=new File(file+".youtube.json");long offset=0,total=-1;
    if(file.isFile()&&checkpoint.isFile()&&checkpoint.length()<4096)try{
      JSONObject saved=new JSONObject(new String(Files.readAllBytes(checkpoint.toPath()),StandardCharsets.UTF_8));
      long length=saved.optLong("length",-1);
      if(identity.equals(saved.optString("identity"))&&length>=0&&length<=file.length()&&saved.optString("sha256").equals(digest(file,length))){offset=length;total=saved.optLong("total",-1);}
    }catch(Exception ignored){}
    transport.check();
    try(RandomAccessFile output=new RandomAccessFile(file,"rw")){
      output.setLength(offset);output.seek(offset);
      MessageDigest hash=MessageDigest.getInstance("SHA-256");
      if(offset>0)try(InputStream input=new FileInputStream(file)){byte[] data=new byte[65536];long left=offset;while(left>0){transport.check();int n=input.read(data,0,(int)Math.min(data.length,left));if(n<0)throw new EOFException("Saved YouTube prefix is incomplete");hash.update(data,0,n);left-=n;}}
      transport.position(offset,total);
      while(total<0||offset<total){
        transport.check();long end=total>0?Math.min(total-1,Math.addExact(offset,chunk-1)):Math.addExact(offset,chunk-1);
        Map<String,String> headers=new LinkedHashMap<>(original);headers.put("Range","bytes="+offset+"-"+end);
        HttpURLConnection connection=transport.open(url,headers);long expected;
        try{
          int status=connection.getResponseCode();if(status!=200&&status!=206)throw DownloadPolicy.HttpFailure.from(connection);
          HttpMedia.ensureIdentityEncoding(connection);
          String type=Optional.ofNullable(connection.getContentType()).orElse("").toLowerCase(Locale.ROOT);
          if(type.contains("html")||type.contains("json"))throw new IOException("YouTube returned an error document instead of media");
          if(status==206){
            Matcher range=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(Optional.ofNullable(connection.getHeaderField("Content-Range")).orElse(""));
            if(!range.matches()||Long.parseLong(range.group(1))!=offset)throw new IOException("YouTube returned an inconsistent chunk range");
            long actualEnd=Long.parseLong(range.group(2)),actualTotal=Long.parseLong(range.group(3));
            if(actualEnd<offset||actualEnd>end||actualTotal<=actualEnd||(total>0&&total!=actualTotal))throw new IOException("YouTube representation length changed");
            total=actualTotal;expected=actualEnd-offset+1;
          }else{
            // A server can ignore Range only for a complete fresh transfer.
            if(offset>0)throw new IOException("YouTube refused the saved resume range");
            expected=connection.getContentLengthLong();if(expected<=0)throw new IOException("YouTube did not provide a complete media length");total=expected;
          }
          if(connection.getContentLengthLong()>=0&&connection.getContentLengthLong()!=expected)throw new IOException("YouTube chunk length differs from its range");
          transport.position(offset,total);long received=0;
          try(InputStream input=connection.getInputStream()){
            byte[] data=new byte[65536];int n;
            while(received<expected&&(n=input.read(data,0,(int)Math.min(data.length,expected-received)))!=-1){transport.check();output.write(data,0,n);hash.update(data,0,n);offset+=n;received+=n;transport.received(n);}
          }
          if(received!=expected)throw new EOFException("YouTube chunk ended before its promised length");
        }finally{
          // Data reaches durable storage before the saved offset. An interrupted
          // request can retain its verified local prefix without recounting it.
          try{output.getFD().sync();save(checkpoint,file,identity,offset,total,hash);}finally{transport.close(connection);}
        }
      }
      if(total<=0||file.length()!=total)throw new EOFException("YouTube file is incomplete");transport.check();return file;
    }
  }
  private static void save(File metadata,File file,String identity,long length,long total,MessageDigest hash)throws Exception {
    String digest;try{digest=hex(((MessageDigest)hash.clone()).digest());}catch(CloneNotSupportedException unavailable){digest=digest(file,length);}
    JSONObject state=new JSONObject().put("identity",identity).put("length",length).put("total",total).put("sha256",digest);
    File temporary=new File(metadata+".new");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(state.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    Files.move(temporary.toPath(),metadata.toPath(),StandardCopyOption.REPLACE_EXISTING);
  }
  private static String digest(File file,long length)throws Exception {MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] data=new byte[65536];while(length>0){int n=in.read(data,0,(int)Math.min(length,data.length));if(n<0)throw new EOFException();hash.update(data,0,n);length-=n;}}return hex(hash.digest());}
  private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format(Locale.ROOT,"%02x",value));return text.toString();}
}

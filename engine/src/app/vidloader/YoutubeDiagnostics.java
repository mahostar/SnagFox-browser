package app.vidloader;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded private stage log. No media addresses, cookies or token values are accepted. */
final class YoutubeDiagnostics {
  static synchronized String read(Context context){try{File file=new File(context.getFilesDir(),"youtube-stages.log");if(!file.isFile())return "No YouTube diagnostics recorded yet.";try(RandomAccessFile input=new RandomAccessFile(file,"r")){int length=(int)Math.min(input.length(),32768);byte[] data=new byte[length];input.seek(input.length()-length);input.readFully(data);return new String(data,StandardCharsets.UTF_8);}}catch(IOException error){return "Could not read local diagnostics.";}}
  static synchronized void record(Context context,String stage,String detail){
    try{File file=new File(context.getFilesDir(),"youtube-stages.log");if(file.length()>128*1024){File previous=new File(file+".previous");java.nio.file.Files.move(file.toPath(),previous.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
      String clean=YoutubeResolver.safeError(new IOException(detail));try(FileOutputStream output=new FileOutputStream(file,true)){output.write((System.currentTimeMillis()+" "+stage+" "+clean+"\n").getBytes(StandardCharsets.UTF_8));}
    }catch(IOException ignored){}
  }
}

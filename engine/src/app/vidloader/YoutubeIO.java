package app.vidloader;
import java.io.*;
final class YoutubeIO {
  static byte[] readAll(InputStream input,int max)throws IOException {ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] data=new byte[8192];int n;while((n=input.read(data))!=-1){if(n>max-output.size())throw new IOException("Input exceeds bound");output.write(data,0,n);}return output.toByteArray();}
  static byte[] readExactly(InputStream input,int length)throws IOException {byte[] data=new byte[length];new DataInputStream(input).readFully(data);return data;}
}

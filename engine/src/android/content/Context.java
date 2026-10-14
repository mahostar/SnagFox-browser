package android.content;
import java.io.*;import java.util.*;
public class Context {
 public static File root,assets; public static final Map<String,Object> settings=new java.util.concurrent.ConcurrentHashMap<>();
 public Context getApplicationContext(){return this;} public File getFilesDir(){root.mkdirs();return root;}
 public Assets getAssets(){return new Assets();} public SharedPreferences getSharedPreferences(String n,int mode){return new SharedPreferences();}
 public static class Assets{public InputStream open(String n)throws IOException{File f=new File(assets,n);if(!f.getCanonicalPath().startsWith(assets.getCanonicalPath()+File.separator))throw new IOException("Invalid asset");return new FileInputStream(f);}}
}
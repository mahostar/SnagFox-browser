package android.content;
public class SharedPreferences {public String getString(String k,String d){return String.valueOf(Context.settings.getOrDefault(k,d));}public int getInt(String k,int d){Object v=Context.settings.get(k);return v instanceof Number?((Number)v).intValue():d;}public boolean getBoolean(String k,boolean d){return Boolean.TRUE.equals(Context.settings.getOrDefault(k,d));}}

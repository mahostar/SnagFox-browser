package app.vidloader;

import java.net.URI;
import java.util.*;

/** URL classification only; YouTube extraction is owned by SnapTubeYoutube. */
final class YoutubeMedia {
  static boolean isYoutubeHost(String value){try{URI uri=URI.create(value);String host=uri.getHost();return "https".equalsIgnoreCase(uri.getScheme())&&uri.getRawUserInfo()==null&&host!=null&&Arrays.asList("youtu.be","youtube.com","www.youtube.com","m.youtube.com","music.youtube.com").contains(host.toLowerCase(Locale.ROOT));}catch(Exception invalid){return false;}}
  static boolean isFeaturePage(String value){
    try{URI uri=URI.create(value);String host=uri.getHost();if(host==null||uri.getRawUserInfo()!=null||!"https".equalsIgnoreCase(uri.getScheme()))return false;
      host=host.toLowerCase(Locale.ROOT);if(host.equals("youtu.be"))return uri.getPath().matches("/[A-Za-z0-9_-]{11}/?");
      if(!Arrays.asList("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com").contains(host))return false;
      return uri.getPath().equals("/watch") || uri.getPath().matches("/(?:shorts|live|embed)/[A-Za-z0-9_-]{11}/?");
    }catch(RuntimeException error){return false;}
  }
}

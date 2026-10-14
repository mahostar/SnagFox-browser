package app.vidloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.IDN;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Browser request rules recovered from ul0 / wm0, plus conservative navigation protection. */
public final class BrowserProtection {
  private final Set<String> hosts = new HashSet<>();
  private static final Pattern HOSTNAME=Pattern.compile("(?=.*[a-z_])[a-z0-9_](?:[a-z0-9_.-]*[a-z0-9_])?");
  private static final Pattern SPACES=Pattern.compile("\\s+");
  private static final String[] NETWORKS = {
      "doubleclick.net", "googlesyndication.com", "adservice.google.com",
      "adsrvr.org", "adnxs.com", "popads.net", "popcash.net"
  };

  public BrowserProtection(Reader rules) throws IOException {
    addHosts(rules);
  }

  /** Loads exact hostnames or standard zero-address HOSTS entries; ignores system mappings. */
  public void addHosts(Reader rules) throws IOException {
    try (BufferedReader reader = new BufferedReader(rules)) {
      String line;
      while ((line = reader.readLine()) != null) {
        int comment=line.indexOf('#');
        if(comment>=0)line=line.substring(0,comment);
        line=line.trim();
        if(line.isEmpty())continue;
        String[] fields=SPACES.split(line);
        if(fields.length>1 && !fields[0].equals("0.0.0.0") && !fields[0].equals("::"))continue;
        for(int i=fields.length==1?0:1;i<fields.length;i++) {
          String host=normalize(fields[i]);
          if(HOSTNAME.matcher(host).matches() && host.indexOf('.')>0)hosts.add(host);
        }
      }
    }
  }

  public int ruleCount() { return hosts.size(); }

  /** Original hm0.j0 / method_128 source-page popup exclusions, recovered via f73. */
  public static boolean sourcePopupBlocked(String source) {
    String sourceHost=host(source);
    for(String fragment:new String[]{"javmost","daftsex","netflav"})
      if(sourceHost.contains(fragment))return true;
    return false;
  }

  /**
   * Original f73 -> s73.o child-window ownership: an untouched child that
   * keeps leaving its opener is discarded after the fourth navigation.
   * The Android owner holds the parent Tab reference and performs the switch.
   */
  public static final class PopupOwnership {
    private String openerHost;
    private boolean touched;
    private int navigations;

    public PopupOwnership(String opener) {
      openerHost=host(opener);
      // f73 excludes Google openers; s73's en_window default is google.com.
      if(openerHost.contains("google."))openerHost="";
    }

    public void noteTouch() { touched=true; }
    public int navigationCount() { return navigations; }

    public boolean shouldReturnToParent(String target) {
      if(touched)openerHost="";
      else navigations++;
      if(openerHost.isEmpty())return false;
      String targetHost=host(target);
      // Match hostname ownership, not a parent hostname planted in an ad query.
      if(targetHost.equals(openerHost) || targetHost.endsWith("."+openerHost)
          || openerHost.contains("google.com")) {
        openerHost="";
        return false;
      }
      return navigations>3;
    }
  }

  public static boolean isAdTag(String url) {
    try {
      URI uri = new URI(url);
      String path = uri.getPath();
      if (path == null) return false;
      path = path.toLowerCase(Locale.ROOT);
      // Endpoint names, never loose substrings such as 'ad' or 'vast' in a media filename.
      if (path.matches(".*/(?:vast|vmap|vpaid|adtag)(?:\\.(?:xml|php|json))?/?$")) return true;
      if (!path.matches(".*/(?:ads|adserver|adtag)(?:\\.(?:php|json))?/?$")) return false;
      String query = uri.getRawQuery();
      if (query == null) return false;
      for (String entry : query.toLowerCase(Locale.ROOT).split("&"))
        if (entry.matches("(?:output|format|type)=(?:vast|vmap|vpaid)")) return true;
      return false;
    } catch (Exception ignored) { return false; }
  }

  public static String emptyAdResponse(String url) {
    boolean vmap=false;
    try {
      URI uri=new URI(url);String path=uri.getPath()==null?"":uri.getPath().toLowerCase(Locale.ROOT);
      vmap=path.matches(".*/vmap(?:\\.(?:xml|php|json))?/?$");
      String query=uri.getRawQuery();
      if(!vmap && isAdTag(url) && query!=null)for(String field:query.toLowerCase(Locale.ROOT).split("&"))
        if(field.matches("(?:output|format|type)=vmap"))vmap=true;
    } catch(Exception ignored){}
    return vmap ? "<?xml version=\"1.0\"?><vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"></vmap:VMAP>"
        : "<?xml version=\"1.0\"?><VAST version=\"3.0\"></VAST>";
  }

  public boolean isAdUrl(String url) {
    String host = host(url);
    if (host.isEmpty()) return false;
    // Observed advertising iframe endpoints; do not block their shared media CDN.
    try {
      URI parsed=new URI(url);
      String path=parsed.getPath();
      // AdGuard Base's host-independent /pu/?psid= rule also matches rotated
      // popunder domains. Bound it to the endpoint and first query key, so a
      // normal /pu/ page or a query mentioning an ad URL remains available.
      if("/pu/".equals(path) && parsed.getRawQuery()!=null
          && parsed.getRawQuery().startsWith("psid="))return true;
      if(host.equals("n2.clips4sale.com") && path!=null && path.startsWith("/a/"))return true;
      if(host.equals("poweredby.jads.co") && "/adshow.php".equals(path))return true;
      if(host.equals("poweredby.jads.co") && "/js/jads.js".equals(path))return true;
      if(host.equals("admaze.exacdn.com") && path!=null && path.matches("/popunder\\d*\\.js"))return true;
      // Independently corroborated card/push advertising hosts (AdGuard Base).
      if(host.equals("gmxes.com") || host.equals("s.smartfloralab.com") || host.equals("a.smartfloralab.com"))return true;
    }catch(Exception ignored){}
    // The original resource sets match exact hosts; never promote a CDN entry to its parent domain.
    if (hosts.contains(normalize(host)) || host.startsWith("svacdn77.")) return true;
    for (String network : NETWORKS)
      if (host.equals(network) || host.endsWith("." + network)) return true;
    return "http".equals(scheme(url)) && (host.equals("bidswitch.net") || host.endsWith(".bidswitch.net"));
  }

  public boolean shouldBlockNavigation(String current, String target, boolean hasGesture,
      boolean isRedirect, boolean isMainFrame, boolean explicitNavigation) {
    if (!isWebUrl(target)) return !"about:blank".equals(target);
    if (explicitNavigation) return false;
    if (isAdUrl(target) || isAdTag(target)) return true;
    if (!isMainFrame) return false; // Unknown cross-origin player frames and APIs remain available.
    if (host(current).isEmpty() || sameSite(current, target)) return false;
    // The caller can carry a bounded permit through redirects belonging to an
    // actual clicked link. A play-button gesture alone does not create a permit.
    return !hasGesture;
  }

  public boolean shouldBlockPopup(String current, String target, boolean hasGesture) {
    return sourcePopupBlocked(current) || !hasGesture || !isWebUrl(target) || isAdUrl(target) || !sameSite(current, target);
  }

  public static boolean isWebUrl(String url) {
    String scheme = scheme(url);
    return ("http".equals(scheme) || "https".equals(scheme)) && !host(url).isEmpty();
  }

  public static boolean sameSite(String first, String second) {
    String a = normalize(host(first)), b = normalize(host(second));
    if (a.isEmpty() || b.isEmpty()) return false;
    if (a.equals(b)) return true;
    // Conservative equivalence: mobile and www aliases only. No guessed public-suffix boundary.
    if (a.startsWith("m.")) a = a.substring(2);
    if (b.startsWith("m.")) b = b.substring(2);
    return a.equals(b);
  }

  public static String host(String url) {
    try {
      URI uri = new URI(url);
      String authority = uri.getRawAuthority();
      if (authority == null || authority.indexOf('@') >= 0) return "";
      String host = uri.getHost();
      if (host == null) {
        // Some original hosts contain underscores; URI.getHost rejects these DNS names.
        host = authority.replaceFirst(":\\d+$", "");
        if (!host.matches("[a-zA-Z0-9_.-]+")) return "";
      }
      return IDN.toASCII(host).toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
    } catch (Exception ignored) { return ""; }
  }

  private static String scheme(String url) {
    try { String value = new URI(url).getScheme(); return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    catch (Exception ignored) { return ""; }
  }

  private static String normalize(String host) {
    host = host.toLowerCase(Locale.ROOT);
    if(host.endsWith("."))host=host.substring(0,host.length()-1);
    return host.startsWith("www.") ? host.substring(4) : host;
  }
}

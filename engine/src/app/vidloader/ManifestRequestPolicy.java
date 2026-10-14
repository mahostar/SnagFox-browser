package app.vidloader;

import java.net.URI;
import java.util.*;

/** r41.e0/S/P request policy, adapted to java.net URI. */
public final class ManifestRequestPolicy {
  private ManifestRequestPolicy() {}

  public static String site(String origin, String page, String target) {
    String source = origin == null || origin.isEmpty() ? page : origin;
    if (source == null || target == null) return "none";
    try {
      URI from = URI.create(source), to = URI.create(target);
      String host = from.getHost(), other = to.getHost();
      if (host == null || other == null) return "none";
      int port = from.getPort() < 0 ? ("https".equalsIgnoreCase(from.getScheme()) ? 443 : 80) : from.getPort();
      int otherPort = to.getPort() < 0 ? ("https".equalsIgnoreCase(to.getScheme()) ? 443 : 80) : to.getPort();
      if (from.getScheme() != null && from.getScheme().equalsIgnoreCase(to.getScheme()) && host.equalsIgnoreCase(other) && port == otherPort) return "same-origin";
      host = host.toLowerCase(Locale.ROOT); other = other.toLowerCase(Locale.ROOT);
      return host.equals(other) || suffix(host).equals(suffix(other)) ? "same-site" : "cross-site";
    } catch (IllegalArgumentException error) { return "none"; }
  }

  private static String suffix(String host) {
    String[] labels = host.split("\\.");
    return labels.length < 2 ? host : labels[labels.length-2]+"."+labels[labels.length-1];
  }

  public static Map<String,String> apply(Map<String,String> context, String target) {
    Map<String,String> headers = new LinkedHashMap<>(context);
    headers.put("Accept", "*/*");
    headers.putIfAbsent("Accept-Language", Locale.getDefault().toLanguageTag());
    headers.put("Sec-Fetch-Mode", "cors");
    headers.put("Sec-Fetch-Dest", "empty");
    headers.put("Sec-Fetch-Site", site(headers.get("Origin"),headers.get("Referer"),target));
    return headers;
  }
}

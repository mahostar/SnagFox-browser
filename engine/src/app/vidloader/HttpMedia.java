package app.vidloader;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** HTTP-only transport shared by discovery and downloads, with bounded redirects and timeouts. */
public final class HttpMedia {
  private HttpMedia() {}

  public interface ConnectionObserver {
    void opened(HttpURLConnection connection) throws IOException;
  }

  public static final class TextResponse {
    public final String text, url;

    TextResponse(String text, String url) {
      this.text = text;
      this.url = url;
    }
  }

  public static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
    return open(url, headers, null);
  }

  public static HttpURLConnection open(
      String url, Map<String, String> headers, ConnectionObserver observer) throws IOException {
    URL next = new URL(url);
    for (int hop = 0; hop < 10; hop++) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Download interrupted");
      if ((!next.getProtocol().equals("https") && !next.getProtocol().equals("http"))
          || next.getHost().isEmpty()
          || next.getUserInfo() != null)
        throw new IOException("Invalid media URL or unsupported scheme");
      HttpURLConnection c = (HttpURLConnection) next.openConnection();
      c.setConnectTimeout(20000);
      c.setReadTimeout(25000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("Accept-Encoding", "identity");
      String currentCookie=android.webkit.CookieManager.getInstance().getCookie(next.toExternalForm());
      if(currentCookie!=null && !currentCookie.isEmpty()) {
        headers=new LinkedHashMap<>(headers);
        headers.keySet().removeIf(k->k.equalsIgnoreCase("Cookie"));
        headers.put("Cookie",currentCookie);
      }
      for (Map.Entry<String, String> h : headers.entrySet())
        if (h.getValue() != null && !h.getValue().isEmpty())
          c.setRequestProperty(h.getKey(), h.getValue());
      int code;
      try {
        if (observer != null) observer.opened(c);
        code = c.getResponseCode();
      } catch (IOException | RuntimeException e) {
        c.disconnect();
        throw e;
      }
      // Cookies belong to the response origin, including non-redirect responses.
      // The browser's domain-aware jar decides where they may next be sent.
      for(Map.Entry<String,List<String>> field:c.getHeaderFields().entrySet())
          if(field.getKey()!=null && field.getKey().equalsIgnoreCase("Set-Cookie") && field.getValue()!=null)
            for(String cookie:field.getValue())android.webkit.CookieManager.getInstance().setCookie(next.toExternalForm(),cookie);
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String location = c.getHeaderField("Location");
        c.disconnect();
        if (location == null || location.trim().isEmpty())
          throw new IOException("Redirect without a destination");
        URL resolved = new URL(next, location);
        if (!sameOrigin(next, resolved)) {
          headers = new LinkedHashMap<>(headers);
          headers
              .keySet()
              .removeIf(k -> k.equalsIgnoreCase("Cookie") || k.equalsIgnoreCase("Authorization") || k.toLowerCase(Locale.ROOT).startsWith("x-goog-") || k.toLowerCase(Locale.ROOT).startsWith("x-youtube-")
                  || k.equalsIgnoreCase("X-FC2-Video-Access-Token"));
        }
        String redirectCookie=android.webkit.CookieManager.getInstance().getCookie(resolved.toExternalForm());
        if(redirectCookie!=null && !redirectCookie.isEmpty()) {
          headers=new LinkedHashMap<>(headers);
          headers.keySet().removeIf(k->k.equalsIgnoreCase("Cookie"));
          headers.put("Cookie",redirectCookie);
        }
        next = resolved;
        continue;
      }
      return c;
    }
    throw new IOException("Too many redirects");
  }

  private static boolean sameOrigin(URL a, URL b) {
    int ap = a.getPort() < 0 ? a.getDefaultPort() : a.getPort(),
        bp = b.getPort() < 0 ? b.getDefaultPort() : b.getPort();
    return a.getProtocol().equalsIgnoreCase(b.getProtocol())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && ap == bp;
  }

  public static String text(String url, Map<String, String> headers, int max) throws IOException {
    return fetchText(url, headers, max, null).text;
  }

  /** Bounded native API transport. Form credentials are never replayed after redirects. */
  public static String postForm(String url,String body,Map<String,String> headers,int max)throws IOException {
    URL endpoint=new URL(url);
    if(!endpoint.getProtocol().equals("https") || endpoint.getUserInfo()!=null)throw new IOException("Invalid API endpoint");
    byte[] form=body.getBytes(StandardCharsets.UTF_8);
    if(form.length>128*1024 || max<=0 || max>1024*1024)throw new IOException("API request exceeds bound");
    HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
    connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(form.length);
    connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
    for(Map.Entry<String,String> entry:headers.entrySet())if(entry.getValue()!=null && !entry.getValue().isEmpty() && !entry.getKey().equalsIgnoreCase("Cookie"))connection.setRequestProperty(entry.getKey(),entry.getValue());
    String cookie=android.webkit.CookieManager.getInstance().getCookie(url);if(cookie!=null && !cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
    try {
      try(OutputStream out=connection.getOutputStream()){out.write(form);}
      int status=connection.getResponseCode();
      for(Map.Entry<String,List<String>> entry:connection.getHeaderFields().entrySet())if(entry.getKey()!=null && entry.getKey().equalsIgnoreCase("Set-Cookie") && entry.getValue()!=null)for(String value:entry.getValue())android.webkit.CookieManager.getInstance().setCookie(url,value);
      if(status!=200)throw new IOException("API response HTTP "+status);
      ensureIdentityEncoding(connection);
      if(connection.getContentLengthLong()>max)throw new IOException("API response exceeds bound");
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(count>max-bytes.size())throw new IOException("API response exceeds bound");bytes.write(buffer,0,count);}}
      if(connection.getContentLengthLong()>=0 && bytes.size()!=connection.getContentLengthLong())throw new EOFException("Incomplete API response");
      return bytes.toString("UTF-8");
    } finally {connection.disconnect();}
  }

  public static TextResponse fetchText(
      String url, Map<String, String> headers, int max, ConnectionObserver observer)
      throws IOException {
    if (max < 0) throw new IllegalArgumentException("Invalid text response limit");
    HttpURLConnection c = open(url, headers, observer);
    try {
      if (c.getResponseCode() < 200 || c.getResponseCode() >= 300)
        throw DownloadPolicy.HttpFailure.from(c);
      ensureIdentityEncoding(c);
      if (c.getContentLengthLong() > max)
        throw new IOException("Manifest exceeds the supported size");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) != -1) {
          if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Download interrupted");
          if (n > max - out.size()) throw new IOException("Manifest exceeds the supported size");
          out.write(b, 0, n);
        }
        if (c.getContentLengthLong() >= 0 && out.size() != c.getContentLengthLong())
          throw new IOException("Incomplete manifest response");
        return new TextResponse(out.toString(StandardCharsets.UTF_8.name()), c.getURL().toString());
      }
    } finally {
      c.disconnect();
    }
  }

  public static void ensureIdentityEncoding(HttpURLConnection c) throws IOException {
    String encoding = c.getHeaderField("Content-Encoding");
    if (encoding != null && !encoding.isEmpty() && !encoding.equalsIgnoreCase("identity"))
      throw new IOException("Server returned an unsupported content encoding");
  }

  public static Map<String, String> headers(DownloadStore.Task t) {
    return headers(t, t.url);
  }

  public static Map<String, String> headers(DownloadStore.Task t, String resourceUrl) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("User-Agent", t.userAgent);
    h.put("Referer", t.referer);
    try {
      if (sameOrigin(new URL(t.url), new URL(resourceUrl))) h.put("Cookie", t.cookie);
    } catch (Exception ignored) {
    }
    try {
      URI r = URI.create(t.referer);
      if (r.getHost() != null) h.put("Origin", r.getScheme() + "://" + r.getRawAuthority());
    } catch (Exception ignored) {
    }
    if (!t.origin.isEmpty()) h.put("Origin", t.origin);
    try{
      if(!t.audioUrl.isEmpty()&&t.audioUrl.equals(resourceUrl))h.putAll(t.audioExtractorHeaders);
      else if(sameOrigin(new URL(t.url),new URL(resourceUrl)))h.putAll(t.extractorHeaders);
    }catch(Exception ignored){}
    return h;
  }
}

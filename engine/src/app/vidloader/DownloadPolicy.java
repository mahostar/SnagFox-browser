package app.vidloader;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.HttpURLConnection;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Transfer policy recovered from tn3.e, r65.T0 and the wg1 connection worker. */
public final class DownloadPolicy {
  private DownloadPolicy() {}

  public static int connections(long bytes) {
    if (bytes < 1048576L) return 1;
    if (bytes < 5242880L) return 2;
    if (bytes < 52428800L) return 3;
    return bytes < 104857600L ? 4 : 5;
  }

  public static final int DEFAULT_RETRIES = 3;

  public static final class HttpFailure extends IOException {
    public final int status;
    public final long retryAfterMillis;
    public final String url;
    public HttpFailure(int status) { this(status, 0, ""); }
    public HttpFailure(int status,long retryAfterMillis,String url) { super("HTTP " + status); this.status = status; this.retryAfterMillis=retryAfterMillis; this.url=url; }
    public static HttpFailure from(HttpURLConnection connection) throws IOException {
      return new HttpFailure(connection.getResponseCode(),retryAfter(connection.getHeaderField("Retry-After"),System.currentTimeMillis()),connection.getURL().toString());
    }
  }

  public static long retryAfter(String value,long now) {
    if(value==null)return 0;
    try {
      long seconds=Long.parseLong(value.trim());
      return seconds<0?0:Math.min(seconds,86400)*1000;
    } catch(NumberFormatException ignored) {
      try { return Math.max(0,Math.min(86400000L,ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now)); }
      catch(RuntimeException invalid){return 0;}
    }
  }

  public static int retryBudget(IOException error,int configured) {
    if(configured<=0)return 0;
    return error instanceof HttpFailure && (((HttpFailure)error).status==429 || ((HttpFailure)error).status==503 || ((HttpFailure)error).status==502 || ((HttpFailure)error).status==504) ? Math.max(configured,8) : configured;
  }

  public static long retryDelay(int attempt,IOException error) {
    long backoff=Math.min(60000L,1000L << Math.min(16,Math.max(0,attempt)));
    return error instanceof HttpFailure ? Math.max(backoff,((HttpFailure)error).retryAfterMillis) : backoff;
  }

  public static boolean retryable(IOException error) {
    if (error instanceof HttpFailure) {
      int code = ((HttpFailure) error).status;
      return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }
    // Storage, invalid ranges, access errors and unsupported media are terminal.
    if (error instanceof java.net.ProtocolException) {
      String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
      if (message.contains("unexpected end of stream")) return true;
    }
    return error instanceof SocketException || error instanceof SocketTimeoutException || error instanceof EOFException || error instanceof java.net.UnknownHostException;
  }
}

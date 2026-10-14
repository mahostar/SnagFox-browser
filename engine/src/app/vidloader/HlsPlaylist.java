package app.vidloader;

import java.util.*;
import java.util.regex.*;

/** A VOD HLS playlist, including per-segment AES-128 and byte-range state. */
public final class HlsPlaylist {
  public static final class Segment {
    public String url, keyUrl = "", iv = "", initUrl = "";
    public long sequence, keyGeneration, offset = -1, length = -1, initOffset = -1, initLength = -1;
  }

  public final List<Segment> segments = new ArrayList<>();
  public boolean ended;
  public double durationSeconds;

  public static Map<String, String> attributes(String line) {
    Map<String, String> map = new LinkedHashMap<>();
    Matcher m = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").matcher(line);
    while (m.find()) map.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3).trim());
    return map;
  }

  public static HlsPlaylist parse(String text, String base) {
    text = ManifestEnvelope.unwrap(text);
    if (text == null || !text.replace("\ufeff", "").trim().startsWith("#EXTM3U"))
      throw new IllegalArgumentException("Not an HLS playlist");
    text = text.replace("\ufeff", "");
    HlsPlaylist p = new HlsPlaylist();
    String key = "", iv = "", init = "", previousUrl = "";
    long seq = 0,
        keyGeneration = 0,
        offset = -1,
        len = -1,
        nextOffset = -1,
        initOffset = -1,
        initLen = -1;
    boolean implicitRange = false, previousWasRange = false;
    for (String raw : text.split("\\r?\\n")) {
      String l = raw.trim();
      if (l.startsWith("#EXTINF:")) {
        double duration = Double.parseDouble(l.substring(8).split(",", 2)[0]);
        if (!Double.isFinite(duration) || duration < 0) throw new IllegalArgumentException("Invalid segment duration");
        p.durationSeconds += duration;
      }
      if (l.startsWith("#EXT-X-STREAM-INF"))
        throw new IllegalArgumentException("Select a quality from this master playlist first");
      if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
        if (!p.segments.isEmpty())
          throw new IllegalArgumentException("Media sequence appears after segments");
        seq = Long.parseLong(l.substring(l.indexOf(':') + 1).trim());
        if (seq < 0) throw new IllegalArgumentException("Invalid media sequence");
      } else if (l.startsWith("#EXT-X-KEY:")) {
        keyGeneration = Math.addExact(keyGeneration, 1);
        Map<String, String> a = attributes(l);
        String method = a.getOrDefault("METHOD", "NONE");
        if (method.equals("NONE")) {
          key = "";
          iv = "";
        } else if (method.equals("AES-128")
            && a.getOrDefault("KEYFORMAT", "identity").equals("identity")) {
          if (!a.containsKey("URI"))
            throw new IllegalArgumentException("Encryption key URI missing");
          key = resolve(base, a.get("URI"));
          iv = a.getOrDefault("IV", "");
          if (!iv.isEmpty() && !iv.matches("(?i)0x[0-9a-f]{1,32}"))
            throw new IllegalArgumentException("Invalid AES-128 IV");
        } else
          throw new IllegalArgumentException(
              "This stream uses protected encryption (" + method + ")");
      } else if (l.startsWith("#EXT-X-MAP:")) {
        String previousInit = init + ":" + initOffset + ":" + initLen;
        Map<String, String> a = attributes(l);
        init = resolve(base, a.get("URI"));
        initOffset = -1;
        initLen = -1;
        if (a.containsKey("BYTERANGE")) {
          String[] r = a.get("BYTERANGE").split("@", -1);
          if (r.length != 2)
            throw new IllegalArgumentException(
                "Initialization byte ranges require an explicit offset");
          initLen = Long.parseLong(r[0]);
          initOffset = Long.parseLong(r[1]);
          if (initLen <= 0 || initOffset < 0)
            throw new IllegalArgumentException("Invalid initialization byte range");
          Math.addExact(initOffset, initLen);
        }
        if (!key.isEmpty())
          throw new IllegalArgumentException(
              "Encrypted fragmented MP4 initialization is unsupported");
        if (!p.segments.isEmpty() && !previousInit.equals(init + ":" + initOffset + ":" + initLen))
          throw new IllegalArgumentException(
              "Changing HLS initialization cannot be packaged safely");
      } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
        if (len >= 0)
          throw new IllegalArgumentException("Byte range repeated before a segment URL");
        String[] r = l.substring(l.indexOf(':') + 1).trim().split("@", -1);
        if (r.length > 2) throw new IllegalArgumentException("Invalid segment byte range");
        len = Long.parseLong(r[0]);
        implicitRange = r.length == 1;
        offset = implicitRange ? nextOffset : Long.parseLong(r[1]);
        if (len <= 0 || offset < 0)
          throw new IllegalArgumentException("Invalid segment byte range");
        Math.addExact(offset, len);
      } else if (l.equals("#EXT-X-DISCONTINUITY")) {
        throw new IllegalArgumentException(
            "HLS discontinuities cannot be packaged safely on this device");
      } else if (l.equals("#EXT-X-GAP") || l.equals("#EXT-X-I-FRAMES-ONLY")) {
        throw new IllegalArgumentException("Incomplete or trick-play HLS is unsupported");
      } else if (l.equals("#EXT-X-ENDLIST")) p.ended = true;
      else if (!l.isEmpty() && !l.startsWith("#")) {
        if (p.ended) throw new IllegalArgumentException("Segment appears after the HLS end marker");
        Segment s = new Segment();
        s.url = resolve(base, l);
        s.keyUrl = key;
        s.iv = iv;
        s.sequence = seq;
        s.keyGeneration = keyGeneration;
        seq = Math.addExact(seq, 1);
        s.initUrl = init;
        s.initOffset = initOffset;
        s.initLength = initLen;
        s.offset = offset;
        s.length = len;
        if (implicitRange && (!previousWasRange || !previousUrl.equals(s.url)))
          throw new IllegalArgumentException(
              "Implicit HLS byte range must follow a range of the same resource");
        if (!key.isEmpty() && offset >= 0)
          throw new IllegalArgumentException("Encrypted byte-range HLS is unsupported");
        p.segments.add(s);
        previousUrl = s.url;
        previousWasRange = offset >= 0;
        nextOffset = previousWasRange ? Math.addExact(offset, len) : -1;
        offset = -1;
        len = -1;
        implicitRange = false;
        if (p.segments.size() > 100000) throw new IllegalArgumentException("Too many HLS segments");
      }
    }
    if (!p.ended)
      throw new IllegalArgumentException(
          "Live streams need recording; this download supports finished videos");
    if (len >= 0) throw new IllegalArgumentException("HLS byte range has no segment URL");
    if (p.segments.isEmpty()) throw new IllegalArgumentException("No media segments in playlist");
    return p;
  }

  public static String resolve(String base, String relative) {
    String resolved = MediaParser.resolve(base, relative);
    if (resolved.isEmpty())
      throw new IllegalArgumentException("Invalid media URL or unsupported scheme");
    return resolved;
  }
}

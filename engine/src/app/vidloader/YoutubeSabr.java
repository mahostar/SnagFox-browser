package app.vidloader;

import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** SABR/UMP request, framing and segment state recovered from UmpRequestHelper/llb/f55. */
final class YoutubeSabr {
  interface Transport {
    HttpURLConnection post(String url,byte[] body)throws IOException;
    void close(HttpURLConnection connection);
    void check()throws IOException;
    void position(long bytes,long total);
    void received(int count);
    String token(boolean refresh)throws Exception;
    void stage(String stage,long done,long total)throws IOException;
  }
  static final class Output {final File video,audio;Output(File video,File audio){this.video=video;this.audio=audio;}}
  private final JSONObject description;private final Transport transport;private final File work;
  private final String videoId;private String endpoint,token="";private byte[] cookie;
  private final List<Track> tracks=new ArrayList<>();private final Map<Integer,Segment> headers=new HashMap<>();
  private final Map<Integer,byte[]> contexts=new LinkedHashMap<>();private final Set<Integer> activeContexts=new LinkedHashSet<>();
  private long backoff,totalBytes,expectedBytes;private int protection,emptyRounds,protectionRetries;
  private static final class Track {
    JSONObject format;String key;File directory;boolean initialized,audio;long endTime,lastSequence=-1;
    final TreeMap<Long,Segment> completed=new TreeMap<>();Segment init;
    byte[] id()throws Exception {YoutubeWire.Writer value=new YoutubeWire.Writer().number(1,format.getInt("itag")).number(2,format.getLong("lastModified"));String tags=format.optString("xtags");if(!tags.isEmpty())value.text(3,tags);return value.build();}
    List<Segment> consumed(){TreeMap<Long,Segment> all=new TreeMap<>(completed);if(init!=null)all.putIfAbsent(init.sequence,init);return new ArrayList<>(all.values());}
    boolean complete(){
      if(!initialized||init==null||lastSequence<0||completed.isEmpty()||completed.lastKey()!=lastSequence)return false;
      long next=completed.firstKey();if(next>1||completed.firstEntry().getValue().start>100)return false;
      for(long sequence:completed.keySet())if(sequence!=next++)return false;return true;
    }
  }
  private static final class Segment {
    Track track;File file,temporary;FileOutputStream output;long sequence,start,duration,length,received;
    boolean init,skip;int compression;byte[] timeRange;
  }
  static Output fetch(String metadata,File directory,Transport transport)throws Exception {return new YoutubeSabr(metadata,directory,transport).run();}
  private YoutubeSabr(String metadata,File directory,Transport transport)throws Exception {
    description=new JSONObject(metadata);this.transport=transport;work=directory;work.mkdirs();videoId=description.getString("videoId");endpoint=description.getString("url");token=description.optString("token");if(token.isEmpty())token=Optional.ofNullable(android.net.Uri.parse(endpoint).getQueryParameter("pot")).orElse("");validateEndpoint(endpoint);
    for(String name:new String[]{"video","audio"}){JSONObject format=description.optJSONObject(name);if(format==null)continue;
      Track track=new Track();track.format=format;track.audio=name.equals("audio");track.key=format.getInt("itag")+";"+format.getLong("lastModified");track.directory=new File(work,YoutubeResolver.fingerprint(videoId+":"+track.key+":"+format.optString("xtags")));track.directory.mkdirs();tracks.add(track);expectedBytes+=Math.max(0,format.optLong("contentLength"));restore(track);
    }
    if(tracks.isEmpty())throw new IOException("SABR has no selected tracks");transport.position(totalBytes,expectedBytes>0?expectedBytes:-1);
  }
  private Output run()throws Exception {
    int failures=0;
    while(!complete()){
      transport.check();waitFor(backoff);backoff=0;protection=0;long before=totalBytes;headers.clear();
      HttpURLConnection connection=null;
      try{
        connection=transport.post(endpoint,request());int status=connection.getResponseCode();if(status!=200)throw DownloadPolicy.HttpFailure.from(connection);
        HttpMedia.ensureIdentityEncoding(connection);
        String type=Optional.ofNullable(connection.getContentType()).orElse("").toLowerCase(Locale.ROOT);if(type.contains("html")||type.contains("json"))throw new IOException("SABR returned an error document");
        try(InputStream stream=connection.getInputStream()){parse(stream);}
        failures=0;
      }catch(IOException error){
        transport.check();if(error instanceof ProtocolFailure||!DownloadPolicy.retryable(error)||failures++>=2)throw error;waitFor(500L*failures);
      }finally{discardIncomplete();if(connection!=null)transport.close(connection);}
      if(complete())break;
      if(protection==2||protection==3){if(++protectionRetries>5)throw new IOException("SABR media authorization could not be renewed");token=transport.token(true);waitFor(Math.max(backoff,500));continue;}
      if(totalBytes==before){if(++emptyRounds>10)throw new IOException("SABR repeatedly returned no new media");waitFor(Math.max(backoff,500));}else{emptyRounds=0;if(protection!=2&&protection!=3)protectionRetries=0;}
    }
    File video=null,audio=null;for(Track track:tracks){transport.check();File combined=combine(track);if(track.audio)audio=combined;else video=combined;}
    return new Output(video,audio);
  }
  private byte[] request()throws Exception {
    long time=Long.MAX_VALUE;boolean hasVideo=false,hasAudio=false;
    for(Track track:tracks){hasVideo|=!track.audio;hasAudio|=track.audio;long end=0;long sequence=-1;for(Segment segment:track.consumed()){if((sequence>=0&&segment.sequence!=sequence+1)||segment.start>end+100)break;sequence=segment.sequence;end=Math.max(end,segment.start+segment.duration);}time=Math.min(time,end);}
    if(time==Long.MAX_VALUE)time=0;
    int height=description.optInt("height",360);YoutubeWire.Writer abr=new YoutubeWire.Writer().number(21,height>0?height:360).number(28,time).number(34,1).floating(35,1f).number(22,0).number(40,hasVideo&&hasAudio?0:hasAudio?1:2);
    YoutubeWire.Writer request=new YoutubeWire.Writer().bytes(1,abr.build()).bytes(5,decode(description.getString("ustreamer")));
    for(Track track:tracks){request.bytes(track.audio?16:17,track.id());if(track.initialized)request.bytes(2,track.id());
      List<Segment> segments=track.consumed();int index=0;
      while(index<segments.size()){
        Segment first=segments.get(index),last=first;long end=first.start+first.duration;index++;
        while(index<segments.size()&&segments.get(index).sequence==last.sequence+1){last=segments.get(index++);end=Math.max(end,last.start+last.duration);}
        YoutubeWire.Writer range=new YoutubeWire.Writer().bytes(1,track.id()).number(2,first.start).number(3,end-first.start).number(4,first.sequence).number(5,last.sequence).bytes(6,new YoutubeWire.Writer().number(1,first.start).number(2,end-first.start).number(3,1000).build());
        request.bytes(3,range.build());
      }
    }
    YoutubeWire.Writer context=new YoutubeWire.Writer().bytes(1,new YoutubeWire.Writer().number(16,1).text(17,"2.20250701.09.00").build()).bytes(3,cookie);
    if(!token.isEmpty())context.bytes(2,decode(token));
    for(Map.Entry<Integer,byte[]> entry:contexts.entrySet())if(activeContexts.contains(entry.getKey()))context.bytes(5,new YoutubeWire.Writer().number(1,entry.getKey()).bytes(2,entry.getValue()).build());else context.number(6,entry.getKey());
    return request.bytes(19,context.build()).build();
  }
  private void parse(InputStream input)throws Exception {
    int frames=0;
    for(;;){transport.check();int first=input.read();if(first<0)return;long type=umpInteger(first,input),length=umpInteger(input.read(),input);if(length<0||length>64L*1024*1024)throw new ProtocolFailure("SABR frame exceeds bound");if(++frames>100000)throw new ProtocolFailure("Too many SABR frames");
      if(type==21){media(input,length);continue;}
      if(length>4*1024*1024)throw new ProtocolFailure("SABR control message exceeds bound");byte[] body=readExactly(input,(int)length);
      switch((int)type){
        case 20:header(body);break;
        case 22:if(body.length>0)finish(body[0]&255);break;
        case 35:{byte[] value=YoutubeWire.bytes(body,7);if(value!=null)cookie=value;backoff=Math.min(60000,Math.max(0,YoutubeWire.number(body,4,0)));String id=YoutubeWire.text(body,8);if(!id.isEmpty()&&!videoId.equals(id))throw new ProtocolFailure("SABR policy returned a different video");break;}
        case 42:initialize(body);break;
        case 43:{String redirected=YoutubeWire.text(body,1);if(!redirected.isEmpty()){validateEndpoint(redirected);endpoint=redirected;}break;}
        case 44:throw new ProtocolFailure("SABR stream error "+YoutubeWire.number(body,2,-1));
        case 57:{int key=(int)YoutubeWire.number(body,1,-1),policy=(int)YoutubeWire.number(body,5,0);byte[] value=YoutubeWire.bytes(body,3);if(key>=0&&value!=null&&!(policy==2&&contexts.containsKey(key))){contexts.put(key,value);if(YoutubeWire.number(body,4,0)!=0)activeContexts.add(key);}if(contexts.size()>512)throw new ProtocolFailure("Too many SABR contexts");break;}
        case 58:protection=(int)YoutubeWire.number(body,1,0);break;
        case 59:for(YoutubeWire.Field field:YoutubeWire.parse(body)){List<Long> ids=new ArrayList<>();if(field.wire==0)ids.add(field.number);else if(field.wire==2&&field.bytes!=null){ByteArrayInputStream packed=new ByteArrayInputStream(field.bytes);while(packed.available()>0)ids.add(YoutubeWire.readVarint(packed));}for(long id:ids){if(field.tag==1)activeContexts.add((int)id);else if(field.tag==2)activeContexts.remove((int)id);else if(field.tag==3)contexts.remove((int)id);}}break;
        default:break;
      }
    }
  }
  private void initialize(byte[] body)throws Exception {
    String id=YoutubeWire.text(body,1);if(!id.isEmpty()&&!videoId.equals(id))throw new ProtocolFailure("SABR format returned a different video");Track track=find(YoutubeWire.bytes(body,2));if(track==null)return;
    track.initialized=true;track.lastSequence=YoutubeWire.number(body,4,-1);track.endTime=YoutubeWire.number(body,3,0);
    write(new File(track.directory,"format.json"),new JSONObject().put("lastSequence",track.lastSequence).put("endTime",track.endTime).toString());
  }
  private void header(byte[] body)throws Exception {
    byte[] format=YoutubeWire.bytes(body,13);Track track=find(format);if(track==null||!track.initialized)return;
    String id=YoutubeWire.text(body,2);if(!id.isEmpty()&&!videoId.equals(id))throw new ProtocolFailure("SABR media returned a different video");
    int header=(int)YoutubeWire.number(body,1,-1);if(header<0||header>255)throw new ProtocolFailure("Invalid SABR media header identifier");
    long compression=YoutubeWire.number(body,7,0);if(compression>2)throw new ProtocolFailure("Unknown SABR media compression");
    Segment segment=new Segment();segment.compression=(int)compression;segment.track=track;segment.sequence=YoutubeWire.number(body,9,0);segment.init=YoutubeWire.number(body,8,0)!=0;segment.length=YoutubeWire.number(body,14,-1);segment.timeRange=YoutubeWire.bytes(body,15);
    segment.start=time(body,11,1,segment.timeRange);segment.duration=time(body,12,2,segment.timeRange);
    segment.file=new File(track.directory,(segment.init?"init-":"media-")+segment.sequence+".bin");segment.temporary=new File(segment.file+".tmp");segment.skip=segment.init?track.init!=null:track.completed.containsKey(segment.sequence);
    if(!segment.skip)segment.output=new FileOutputStream(segment.temporary);Segment previous=headers.put(header,segment);if(previous!=null&&previous.output!=null){previous.output.close();Files.deleteIfExists(previous.temporary.toPath());}
  }
  private void media(InputStream input,long length)throws Exception {
    if(length<1)throw new ProtocolFailure("Empty SABR media frame");int id=input.read();if(id<0)throw new EOFException();length--;Segment segment=headers.get(id);byte[] data=new byte[65536];
    while(length>0){transport.check();int n=input.read(data,0,(int)Math.min(data.length,length));if(n<0)throw new EOFException("SABR media frame interrupted");length-=n;
      if(segment!=null&&!segment.skip){if(segment.received+n>256L*1024*1024||(segment.compression!=2&&segment.length>=0&&segment.received+n>segment.length))throw new ProtocolFailure("SABR segment exceeds its declared length");segment.output.write(data,0,n);segment.received+=n;transport.received(n);}
    }
  }
  private void finish(int header)throws Exception {
    Segment segment=headers.remove(header);if(segment==null||segment.skip)return;
    try{segment.output.getFD().sync();}finally{segment.output.close();segment.output=null;}
    long rawLength=segment.received;
    if(segment.compression==2){
      boolean gzip;try(InputStream input=new FileInputStream(segment.temporary)){gzip=input.read()==31&&input.read()==139;}
      if(gzip){File decoded=new File(segment.temporary+".decoded");long length=0;try(InputStream input=new java.util.zip.GZIPInputStream(new FileInputStream(segment.temporary));FileOutputStream output=new FileOutputStream(decoded)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){transport.check();length+=count;if(length>256L*1024*1024)throw new ProtocolFailure("Decoded SABR media exceeds bound");output.write(buffer,0,count);}output.getFD().sync();}Files.move(decoded.toPath(),segment.temporary.toPath(),StandardCopyOption.REPLACE_EXISTING);segment.received=length;}
    }
    if(segment.received<=0||(segment.length>=0&&segment.received!=segment.length&&rawLength!=segment.length))throw new EOFException("SABR segment ended before its promised length");
    transport.check();Files.move(segment.temporary.toPath(),segment.file.toPath(),StandardCopyOption.REPLACE_EXISTING);
    JSONObject state=new JSONObject().put("sequence",segment.sequence).put("init",segment.init).put("start",segment.start).put("duration",segment.duration).put("length",segment.received).put("sha256",DirectRangeCheckpoint.digest(segment.file));
    if(segment.timeRange!=null)state.put("timeRange",Base64.encodeToString(segment.timeRange,Base64.NO_WRAP));write(new File(segment.file+".json"),state.toString());
    totalBytes+=segment.received;if(segment.init)segment.track.init=segment;else segment.track.completed.put(segment.sequence,segment);
  }
  private void restore(Track track)throws Exception {
    File state=new File(track.directory,"format.json");if(state.isFile()&&state.length()<4096)try{JSONObject json=new JSONObject(new String(Files.readAllBytes(state.toPath()),StandardCharsets.UTF_8));track.lastSequence=json.optLong("lastSequence",-1);track.endTime=json.optLong("endTime");track.initialized=true;}catch(Exception ignored){}
    File[] files=track.directory.listFiles((directory,name)->name.endsWith(".bin.json"));if(files==null)return;
    for(File metadata:files){transport.check();if(metadata.length()>4096)continue;try{JSONObject json=new JSONObject(new String(Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));File file=new File(metadata.getPath().substring(0,metadata.getPath().length()-5));if(!file.isFile()||file.length()!=json.optLong("length")||!DirectRangeCheckpoint.digest(file).equals(json.optString("sha256")))continue;
      Segment segment=new Segment();segment.track=track;segment.file=file;segment.sequence=json.getLong("sequence");segment.init=json.optBoolean("init");segment.start=json.optLong("start");segment.duration=json.optLong("duration");segment.received=file.length();if(json.has("timeRange"))segment.timeRange=decode(json.getString("timeRange"));if(segment.init)track.init=segment;else track.completed.put(segment.sequence,segment);totalBytes+=file.length();
    }catch(Exception ignored){}}
  }
  private File combine(Track track)throws Exception {
    File result=new File(work,track.audio?"audio.media":"video.media");long length=track.init.file.length();for(Segment segment:track.completed.values())length+=segment.file.length();long done=0;
    try(FileOutputStream output=new FileOutputStream(result)){List<Segment> order=new ArrayList<>();order.add(track.init);order.addAll(track.completed.values());byte[] data=new byte[65536];for(Segment segment:order)try(InputStream input=new FileInputStream(segment.file)){int n;while((n=input.read(data))!=-1){transport.check();output.write(data,0,n);done+=n;transport.stage("assembling",done,length);}}output.getFD().sync();}
    if(result.length()!=length)throw new EOFException("SABR assembly length differs");return result;
  }
  private Track find(byte[] format)throws Exception {if(format==null)return null;String key=YoutubeWire.number(format,1,-1)+";"+YoutubeWire.number(format,2,-1);String tags=YoutubeWire.text(format,3);for(Track track:tracks)if(track.key.equals(key)&&(tags.isEmpty()||track.format.optString("xtags").isEmpty()||tags.equals(track.format.optString("xtags"))))return track;return null;}
  private boolean complete(){for(Track track:tracks)if(!track.complete())return false;return true;}
  private void discardIncomplete()throws IOException {for(Segment segment:headers.values())if(segment.output!=null){try{segment.output.close();}finally{Files.deleteIfExists(segment.temporary.toPath());}}headers.clear();transport.position(totalBytes,expectedBytes>0?expectedBytes:-1);}
  private void waitFor(long ms)throws Exception {long end=android.os.SystemClock.elapsedRealtime()+Math.min(ms,60000);while(android.os.SystemClock.elapsedRealtime()<end){transport.check();Thread.sleep(Math.max(1,Math.min(100,end-android.os.SystemClock.elapsedRealtime())));}}
  private static long time(byte[] message,int direct,int tag,byte[] range)throws Exception {long value=YoutubeWire.number(message,direct,-1);if(value>=0)return value;if(range==null)return 0;long scale=YoutubeWire.number(range,3,0);return scale>0?Math.multiplyExact(YoutubeWire.number(range,tag,0),1000)/scale:0;}
  private static void validateEndpoint(String address)throws Exception {URL url=new URL(address);if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||!(url.getHost().equals("googlevideo.com")||url.getHost().endsWith(".googlevideo.com")))throw new ProtocolFailure("Unexpected SABR endpoint");}
  private static byte[] decode(String value){return Base64.decode(value.replace('-','+').replace('_','/').replace('.','='),Base64.DEFAULT);}
  private static long umpInteger(int first,InputStream input)throws IOException {
    if(first<0)throw new EOFException("Truncated UMP integer");if(first<128)return first;
    int count=first<192?1:first<224?2:first<240?3:4;byte[] next=readExactly(input,count);
    if(count==4)return ((long)(next[0]&255)<<24)|((long)(next[1]&255)<<16)|((long)(next[2]&255)<<8)|(next[3]&255);
    int bits=7-count;long value=first&((1<<bits)-1);for(int i=0;i<count;i++)value|=(long)(next[i]&255)<<(bits+i*8);return value;
  }
  private static byte[] readExactly(InputStream input,int length)throws IOException {byte[] bytes=new byte[length];new DataInputStream(input).readFully(bytes);return bytes;}
  private static void write(File file,String text)throws Exception {File temporary=new File(file+".new");try(FileOutputStream output=new FileOutputStream(temporary)){output.write(text.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
  private static final class ProtocolFailure extends IOException {ProtocolFailure(String message){super(message);}}
}

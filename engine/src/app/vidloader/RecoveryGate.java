package app.vidloader;

import java.io.*;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared per-origin cooldown; overloaded origins temporarily use one request at a time. */
public final class RecoveryGate {
  public interface Check { void run() throws IOException; }
  private static final class Host { int active,limit=6,successes; long until; }
  private final ConcurrentHashMap<String,Host> hosts=new ConcurrentHashMap<>();
  private Host host(String url) {
    URI uri=URI.create(url);
    String key=uri.getScheme()+"://"+uri.getHost()+":"+(uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort());
    return hosts.computeIfAbsent(key,ignored->new Host());
  }
  public final class Lease implements AutoCloseable {
    private final Host host;
    private final AtomicBoolean closed=new AtomicBoolean();
    private Lease(Host host){this.host=host;}
    public void success(){synchronized(host){if(host.limit==1 && ++host.successes>=8){host.limit=6;host.notifyAll();}}}
    public void close(){if(closed.compareAndSet(false,true))synchronized(host){host.active--;host.notifyAll();}}
  }
  public Lease acquire(String url,Check check) throws IOException {
    Host host=host(url);
    for(;;){
      check.run();
      synchronized(host){
        if(System.currentTimeMillis()>=host.until && host.active<host.limit){host.active++;return new Lease(host);}
        try{host.wait(100);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("Download interrupted");}
      }
    }
  }
  public long defer(String url,long delay,boolean overloaded){
    Host host=host(url);
    synchronized(host){host.until=Math.max(host.until,System.currentTimeMillis()+delay);if(overloaded){host.limit=1;host.successes=0;}host.notifyAll();return host.until;}
  }
}

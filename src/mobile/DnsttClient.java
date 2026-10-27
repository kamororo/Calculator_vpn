package mobile;
/** Gomobile proxy for the DNSTT client in
 *  SlipNet's libgojni. Method names and types
 *  must match the generated bindings exactly:
 *  Go int -> Java long, Go string -> String,
 *  Go bool -> boolean. __New is how Go returns
 *  a new client object to Java. */
public class DnsttClient {
    private long ref;
    public DnsttClient(long ref) { this.ref = ref; }
    public static native DnsttClient __New(long ref);
    public native void start();
    public native void stop();
    public native boolean isRunning();
    public native void setAuthoritativeMode(boolean v);
    public native void setDeviceManufacturer(String v);
    public native void setEDNS0Size(long v);
    public native void setMaxPayload(long v);
    public native void setNoizMode(boolean v);
    public native void setRRSpreadCount(long v);
    public native void setResolverMode(String v);
    public native void setSOCKS5Proxy(String addr, String user, String pass);
    public native void setSocksCredentials(String user, String pass);
    public native void setStealthMode(boolean v);
    public native void setUTLSFingerprint(String v);
}

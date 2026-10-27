package vaydns;
/** Gomobile proxy for the VayDNS client in
 *  SlipNet's libgojni. Types as in DnsttClient,
 *  except RPS which is a Go float64 -> Java double. */
public class VaydnsClient {
    private long ref;
    public VaydnsClient(long ref) { this.ref = ref; }
    public static native VaydnsClient __New(long ref);
    public native void start();
    public native void stop();
    public native boolean isRunning();
    public native void setClientIDSize(long v);
    public native void setDnsttCompat(boolean v);
    public native void setIdleTimeout(long v);
    public native void setKeepAlive(long v);
    public native void setMaxNumLabels(long v);
    public native void setMaxPayload(long v);
    public native void setMaxQnameLen(long v);
    public native void setRPS(double v);
    public native void setRRSpreadCount(long v);
    public native void setRecordType(String v);
    public native void setResolverMode(String v);
    public native void setSocksCredentials(String user, String pass);
    public native void setUDPTimeout(long v);
    public native void setUTLSFingerprint(String v);
}

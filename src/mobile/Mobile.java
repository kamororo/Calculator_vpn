package mobile;
/** Gomobile entry point for DNSTT (and NoizDNS,
 *  which is the same client with noiz mode on). */
public class Mobile {
    static {
        System.loadLibrary("slipnet-go");
        _init();
    }
    private static native void _init();
    public static native DnsttClient newClient(String dnsServer, String tunnelDomain, String publicKey, String listenAddr);
}

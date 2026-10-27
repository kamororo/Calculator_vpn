package vaydns;
/** Gomobile entry point for VayDNS. */
public class Vaydns {
    static {
        System.loadLibrary("slipnet-go");
        _init();
    }
    private static native void _init();
    public static native VaydnsClient newClient(String dnsServer, String tunnelDomain, String publicKey, String listenAddr);
}

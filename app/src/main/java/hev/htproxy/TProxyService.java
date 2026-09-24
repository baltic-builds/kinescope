package hev.htproxy;

/** JNI contract used by the upstream hev-socks5-tunnel Android library. */
public final class TProxyService {
    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}

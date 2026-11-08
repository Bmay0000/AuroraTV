package tv.aurora.player;

/**
 * Optional bridge to the Rust core. The Java implementation remains a
 * deliberate fallback for old APKs, tests, and installations missing a native ABI.
 * This class never logs credentials, resolved stream URLs or exceptions.
 */
public final class RustBridge {
    private static final boolean AVAILABLE;
    static {
        boolean loaded=false;
        try {System.loadLibrary("auroratv_native");loaded=true;}
        catch (LinkageError ignored) { /* Java fallback */ }
        AVAILABLE=loaded;
    }
    private RustBridge() {}
    public static boolean available() {return AVAILABLE;}

    private static native String nativeReference(String kind,String id,String ext);
    private static native String nativeResolve(String reference,String server,String user,String password);
    private static native int nativeChannelScore(String network,String channel,String category);
    private static native String nativeNormalizeChannel(String name);

    public static String reference(String kind,String id,String ext) {
        if (!AVAILABLE) return null;
        try {return nativeReference(kind,id,ext);}
        catch (LinkageError ignored) {return null;}
    }
    public static String resolve(String reference,String server,String user,String password) {
        if (!AVAILABLE) return null;
        try {return nativeResolve(reference,server,user,password);}
        catch (LinkageError ignored) {return null;}
    }
    public static int scoreUsNetwork(String network,String name,String category) {
        if (!AVAILABLE) return 0;
        try {return nativeChannelScore(network,name,category);}
        catch (LinkageError ignored) {return 0;}
    }
    public static String normalizedChannel(String name) {
        if (!AVAILABLE) return null;
        try {return nativeNormalizeChannel(name);}
        catch (LinkageError ignored) {return null;}
    }
}

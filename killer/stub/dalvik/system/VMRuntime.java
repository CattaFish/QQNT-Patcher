package dalvik.system;

public final class VMRuntime {
    public static VMRuntime getRuntime() { return null; }
    public native void setHiddenApiExemptions(String[] exemptions);
}

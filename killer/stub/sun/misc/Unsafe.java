package sun.misc;
import java.lang.reflect.Field;

public final class Unsafe {
    private static final Unsafe theUnsafe = new Unsafe();
    private Unsafe() {}
    public static Unsafe getUnsafe() { return theUnsafe; }
    public native long objectFieldOffset(Field field);
    public native long getLong(Object obj, long offset);
    public native void putLong(Object obj, long offset, long value);
    public native int getInt(long address);
    public native int getInt(Object obj, long offset);
    public native long getLong(long address);
    public native void putLong(long address, long value);
}

package com.tencent.qqnt.patch.killer;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import dalvik.system.VMRuntime;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import sun.misc.Unsafe;

public class App extends Application {
    public static final String URL = "https://github.com/L-JINBIN/ApkSignatureKillerEx";

    public static class a { private boolean override; }

    public static final class b {
        private transient int accessFlags;
        private transient int classFlags;
        private transient ClassLoader classLoader;
        private transient int classSize;
        private transient int clinitThreadId;
        private transient Class componentType;
        private transient short copiedMethodsOffset;
        private transient Object dexCache;
        private transient int dexClassDefIndex;
        private volatile transient int dexTypeIndex;
        private transient Object extData;
        private transient long iFields;
        private transient Object[] ifTable;
        private transient long methods;
        private transient String name;
        private transient int numReferenceInstanceFields;
        private transient int numReferenceStaticFields;
        private transient int objectSize;
        private transient int objectSizeAllocFastPath;
        private transient int primitiveType;
        private transient int referenceInstanceOffsets;
        private transient long sFields;
        private transient int status;
        private transient Class superClass;
        private transient short virtualMethodsOffset;
        private transient Object vtable;
    }

    public static final class c extends a {
        private int accessFlags;
        private long artMethod;
        private b declaringClass;
        private b declaringClassOfOverriddenMethod;
        private Object[] parameters;
    }

    public static final class d {
        private final Member member = null;
        private final f handle = null;
    }

    public static final class e {
        private e(Object... objArr) { throw new IllegalStateException("Failed to new a instance"); }
        private static Object invoke(Object... objArr) { throw new IllegalStateException("Failed to invoke the method"); }
    }

    public static class f {
        private f cachedSpreadInvoker;
        private MethodType nominalType;
        private final MethodType type = null;
        protected final int handleKind = 0;
        protected final long artFieldOrMethod = 0;
    }

    public static final class g extends f {
        private final MethodHandleInfo info = null;
    }

    public static final class h {
        private static int s;
        private static int t;
        private int i;
        private int j;
        private static void a() {}
        private static void b() {}
    }

    public static final class i {}

    public static final class j {
        public static final Unsafe a;
        public static final long b;
        public static final long c;
        public static final long d;
        public static final long e;
        public static final HashSet f = new HashSet();
        static {
            try {
                Unsafe unsafe = (Unsafe) Unsafe.class.getDeclaredMethod("getUnsafe", null).invoke(null, null);
                a = unsafe;
                b = unsafe.objectFieldOffset(c.class.getDeclaredField("artMethod"));
                unsafe.objectFieldOffset(c.class.getDeclaredField("declaringClass"));
                long jObjectFieldOffset = unsafe.objectFieldOffset(f.class.getDeclaredField("artFieldOrMethod"));
                unsafe.objectFieldOffset(g.class.getDeclaredField("info"));
                long jObjectFieldOffset2 = unsafe.objectFieldOffset(b.class.getDeclaredField("methods"));
                c = jObjectFieldOffset2;
                long jObjectFieldOffset3 = unsafe.objectFieldOffset(b.class.getDeclaredField("iFields"));
                unsafe.objectFieldOffset(b.class.getDeclaredField("sFields"));
                unsafe.objectFieldOffset(d.class.getDeclaredField("member"));
                Method declaredMethod = h.class.getDeclaredMethod("a", null);
                Method declaredMethod2 = h.class.getDeclaredMethod("b", null);
                declaredMethod.setAccessible(true);
                declaredMethod2.setAccessible(true);
                MethodHandle methodHandleUnreflect = MethodHandles.lookup().unreflect(declaredMethod);
                MethodHandle methodHandleUnreflect2 = MethodHandles.lookup().unreflect(declaredMethod2);
                long j = unsafe.getLong(methodHandleUnreflect, jObjectFieldOffset);
                long j2 = unsafe.getLong(methodHandleUnreflect2, jObjectFieldOffset);
                long j3 = unsafe.getLong(h.class, jObjectFieldOffset2);
                long j4 = j2 - j;
                d = j4;
                e = (j - j3) - j4;
                Field declaredField = h.class.getDeclaredField("i");
                Field declaredField2 = h.class.getDeclaredField("j");
                declaredField.setAccessible(true);
                declaredField2.setAccessible(true);
                MethodHandle methodHandleUnreflectGetter = MethodHandles.lookup().unreflectGetter(declaredField);
                MethodHandle methodHandleUnreflectGetter2 = MethodHandles.lookup().unreflectGetter(declaredField2);
                unsafe.getLong(methodHandleUnreflectGetter, jObjectFieldOffset);
                unsafe.getLong(methodHandleUnreflectGetter2, jObjectFieldOffset);
                unsafe.getLong(h.class, jObjectFieldOffset3);
            } catch (ReflectiveOperationException e2) {
                Log.e("HiddenApiBypass", "Initialize error", e2);
                throw new ExceptionInInitializerError(e2);
            }
        }

        public static Object a(Class cls, Object obj, String str, Object... objArr)
                throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
            int i = 0;
            if (obj != null && !cls.isInstance(obj)) {
                throw new IllegalArgumentException("this object is not an instance of the given class");
            }
            Method declaredMethod = e.class.getDeclaredMethod("invoke", Object[].class);
            declaredMethod.setAccessible(true);
            Unsafe unsafe = a;
            long j = unsafe.getLong(cls, c);
            if (j == 0) throw new NoSuchMethodException("Cannot find matching method");
            int i2 = unsafe.getInt(j);
            for (int i3 = 0; i3 < i2; i3++) {
                a.putLong(declaredMethod, b, (((long) i3) * d) + j + e);
                if (str.equals(declaredMethod.getName())) {
                    Class<?>[] parameterTypes = declaredMethod.getParameterTypes();
                    if (parameterTypes.length == objArr.length) {
                        while (i < parameterTypes.length) {
                            if (parameterTypes[i].isPrimitive()) {
                                Class<?> cls2 = parameterTypes[i];
                                i = ((cls2 != Integer.TYPE || (objArr[i] instanceof Integer))
                                        && (cls2 != Byte.TYPE || (objArr[i] instanceof Byte))
                                        && ((cls2 != Character.TYPE || (objArr[i] instanceof Character))
                                        && ((cls2 != Boolean.TYPE || (objArr[i] instanceof Boolean))
                                        && ((cls2 != Double.TYPE || (objArr[i] instanceof Double))
                                        && ((cls2 != Float.TYPE || (objArr[i] instanceof Float))
                                        && ((cls2 != Long.TYPE || (objArr[i] instanceof Long))
                                        && (cls2 != Short.TYPE || (objArr[i] instanceof Short)))))))) ? i + 1 : 0;
                            } else {
                                Object obj2 = objArr[i];
                                if (obj2 == null || parameterTypes[i].isInstance(obj2)) { }
                            }
                        }
                        return declaredMethod.invoke(obj, objArr);
                    }
                }
            }
            throw new NoSuchMethodException("Cannot find matching method");
        }

        public static boolean b(String... strArr) {
            try {
                a(VMRuntime.class, a(VMRuntime.class, null, "getRuntime", new Object[0]), "setHiddenApiExemptions", strArr);
                return true;
            } catch (Throwable th) {
                Log.w("HiddenApiBypass", "setHiddenApiExemptions", th);
                return false;
            }
        }
    }

    public static final class k {}

    public static final class l implements Parcelable.Creator {
        public final Parcelable.Creator a;
        public final String b;
        public final Signature c;

        public l(Parcelable.Creator creator, String str, Signature signature) {
            this.a = creator;
            this.b = str;
            this.c = signature;
        }

        @Override
        public final Object createFromParcel(Parcel parcel) {
            Signature[] apkContentsSigners;
            PackageInfo packageInfo = (PackageInfo) this.a.createFromParcel(parcel);
            if (packageInfo.packageName.equals(this.b)) {
                Signature[] signatureArr = packageInfo.signatures;
                Signature signature = this.c;
                if (signatureArr != null && signatureArr.length > 0) signatureArr[0] = signature;
                if (Build.VERSION.SDK_INT >= 28 && packageInfo.signingInfo != null
                        && (apkContentsSigners = packageInfo.signingInfo.getApkContentsSigners()) != null
                        && apkContentsSigners.length > 0) {
                    apkContentsSigners[0] = signature;
                }
            }
            return packageInfo;
        }

        @Override
        public final Object[] newArray(int i) {
            return (PackageInfo[]) this.a.newArray(i);
        }
    }

    static {
        killPM("%%TARGET_PKG%%", "%%SIGNATURE_B64%%");
        killOpen("%%TARGET_PKG%%", "%%LIB_NAME%%", "%%ASSET_PATH%%", "%%FILES_DIR%%");
    }

    public static Field a(Class clazz, String name) throws NoSuchFieldException {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            while (true) {
                clazz = clazz.getSuperclass();
                if (clazz == null || clazz.equals(Object.class)) break;
                try {
                    Field field = clazz.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) { }
            }
            throw e;
        }
    }

    public static boolean b(String packageName, String path) {
        if (path.startsWith("/") && path.endsWith(".apk")) {
            String[] arr = path.substring(1).split("/", 6);
            int len = arr.length;
            if (len == 4 || len == 5) {
                if (arr[0].equals("data") && arr[1].equals("app") && arr[len - 1].equals("base.apk"))
                    return arr[len - 2].startsWith(packageName);
                if (arr[0].equals("mnt") && arr[1].equals("asec") && arr[len - 1].equals("pkg.apk"))
                    return arr[len - 2].startsWith(packageName);
            } else if (len == 3) {
                if (arr[0].equals("data") && arr[1].equals("app")) return arr[2].startsWith(packageName);
            } else if (len == 6 && arr[0].equals("mnt") && arr[1].equals("expand")
                    && arr[3].equals("app") && arr[5].equals("base.apk")) {
                return arr[4].endsWith(packageName);
            }
        }
        return false;
    }

    private static void killOpen(String targetPkg, String libName, String assetPath, String outDir) {
        if (targetPkg.isEmpty() || libName.isEmpty() || assetPath.isEmpty() || outDir.isEmpty()) return;
        String apkPath = null;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                String path = parts[parts.length - 1];
                if (b(targetPkg, path)) { apkPath = path; break; }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (apkPath == null) { System.err.println("Get apk path failed"); return; }
        File apkFile = new File(apkPath);
        File dataDir;
        String username = Environment.getExternalStorageDirectory().getName();
        if (username.matches("\\d+")) {
            File userDir = new File("/data/user/" + username + "/" + targetPkg);
            if (userDir.canWrite()) dataDir = userDir;
            else dataDir = new File("/data/data/" + targetPkg);
        } else {
            dataDir = new File("/data/data/" + targetPkg);
        }
        File repFile = new File(dataDir, outDir);
        repFile.getParentFile().mkdirs();
        cleanStaleTmp(repFile);
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            ZipEntry entry = zipFile.getEntry(assetPath);
            if (entry == null) throw new RuntimeException("Entry not found: " + assetPath);
            if (!repFile.exists() || repFile.length() != entry.getSize()) {
                // 原子写入：先写 <repFile>.tmp.<pid>，再 rename 覆盖。
                // Linux rename(2) 同目录下原子替换，并发读者只会看到完整文件。
                File tmpFile = new File(repFile.getAbsolutePath() + ".tmp." + Process.myPid());
                try (InputStream is = zipFile.getInputStream(entry);
                     OutputStream os = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[102400];
                    int len;
                    while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
                }
                if (!tmpFile.renameTo(repFile)) {
                    // 某些文件系统不允许 rename 覆盖已存在目标，退化为 delete + rename
                    if (repFile.exists() && !repFile.delete()) {
                        throw new RuntimeException("Cannot replace " + repFile);
                    }
                    if (!tmpFile.renameTo(repFile)) {
                        throw new RuntimeException("Rename failed: " + tmpFile + " -> " + repFile);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.setProperty("mt.signature.killer.path1", apkFile.getAbsolutePath());
        System.setProperty("mt.signature.killer.path2", repFile.getAbsolutePath());
        try {
            System.loadLibrary(libName);
        } finally {
            System.clearProperty("mt.signature.killer.path1");
            System.clearProperty("mt.signature.killer.path2");
        }
    }

    private static void cleanStaleTmp(File repFile) {
        try {
            File parent = repFile.getParentFile();
            if (parent == null) return;
            final String prefix = repFile.getName() + ".tmp.";
            File[] files = parent.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            int myPid = Process.myPid();
            for (File f : files) {
                String name = f.getName();
                if (!name.startsWith(prefix)) continue;
                String pidStr = name.substring(prefix.length());
                long pid;
                try {
                    pid = Long.parseLong(pidStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == myPid) continue;
                boolean pidAlive = new File("/proc/" + pid).exists();
                boolean tooOld = (now - f.lastModified()) > 3600000L;
                if (pidAlive && !tooOld) continue;
                if (!f.delete()) {
                    System.err.println("cleanStaleTmp: failed to delete " + f);
                }
            }
        } catch (Throwable t) {
            System.err.println("cleanStaleTmp: " + t);
        }
    }

    private static void killPM(String targetPkg, String signatureBase64) {
        try {
            a(PackageInfo.class, "CREATOR").set(null,
                    new l(PackageInfo.CREATOR, targetPkg, new Signature(Base64.decode(signatureBase64, 0))));
            if (Build.VERSION.SDK_INT >= 28) {
                HashSet hashSet = j.f;
                hashSet.addAll(Arrays.asList("Landroid/os/Parcel;", "Landroid/content/pm", "Landroid/app"));
                String[] strArr = new String[hashSet.size()];
                hashSet.toArray(strArr);
                j.b(strArr);
            }
            try {
                Object obj = a(PackageManager.class, "sPackageInfoCache").get(null);
                obj.getClass().getMethod("clear", null).invoke(obj, null);
            } catch (Throwable ignored) { }
            try {
                ((Map) a(Parcel.class, "mCreators").get(null)).clear();
            } catch (Throwable ignored) { }
            try {
                ((Map) a(Parcel.class, "sPairedCreators").get(null)).clear();
            } catch (Throwable ignored) { }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

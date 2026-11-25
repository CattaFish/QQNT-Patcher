package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.patch.util.PLog;

import bsh.BshMethod;
import bsh.Interpreter;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginCompiler {

    private final Context mContext;
    private final File mPluginDir;
    private final String mPluginId;
    public Interpreter interpreter = new Interpreter();
    private volatile boolean mIsRunning = false;
    private volatile String mLastError = "";
    public final Map<String, String> menuItems = new LinkedHashMap<>();
    public final FixClassLoader loader;

    private static final Map<String, MsgMenuItemInfo> sAllMsgMenuItems = new ConcurrentHashMap<>();

    public static class MsgMenuItemInfo {
        public String pluginId;
        public String name;
        public String callback;
        public int[] msgTypes;
        public MsgMenuItemInfo(String pId, String name, String cb, int[] types) {
            this.pluginId = pId; this.name = name; this.callback = cb; this.msgTypes = types;
        }
    }

    public static Map<String, MsgMenuItemInfo> getAllMsgMenuItems() {
        return Collections.unmodifiableMap(sAllMsgMenuItems);
    }

    public PluginCompiler(Context context, File pluginDir, ClassLoader bshClassLoader) {
        this.mContext = context;
        this.mPluginDir = pluginDir;
        this.mPluginId = pluginDir.getName();
        this.loader = new FixClassLoader(context.getClassLoader(), bshClassLoader);
    }

    public String getPluginId() { return mPluginId; }
    public Map<String, String> getMenuItems() { return menuItems; }
    public FixClassLoader getClassLoader() { return loader; }
    public Object getInterpreter() { return interpreter; }
    public boolean isRunning() { return mIsRunning; }
    public String getLastError() { return mLastError; }

    public void addMenuItem(String name, String callback) {
        menuItems.put(name, callback);
        PLog.i("Plugin", "[" + mPluginId + "] 注册悬浮球动作: " + name + " -> " + callback);
    }

    public void addMsgMenuItem(String name, String callback, int[] msgTypes) {
        String key = mPluginId + "_" + name;
        sAllMsgMenuItems.put(key, new MsgMenuItemInfo(mPluginId, name, callback, msgTypes));
        PLog.i("Plugin", "[" + mPluginId + "] 注册气泡长按菜单: " + name + " -> " + callback);
    }

    public void loadJava(String path) {
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            File f = new File(path);
            if (!f.exists()) return;
            interpreter.source(f.getAbsolutePath());
            PLog.i("Plugin", "[" + mPluginId + "] 成功 source 外联类: " + f.getName());
        } catch (Throwable t) {
            logError("loadJava 异常 [" + path + "]:\n" + getStackTrace(t));
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public synchronized boolean start() {
        if (mIsRunning) stop();
        menuItems.clear();
        mLastError = "";

        File scriptFile = new File(mPluginDir, "main.java");
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            mLastError = "main.java 不存在";
            PLog.e("Plugin", "[" + mPluginId + "] 启动失败: " + mLastError);
            return false;
        }

        long tStart = System.currentTimeMillis();
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);

        try {
            interpreter = new Interpreter();

            String myUin = MsgSender.getMyUin();
            if (myUin == null) myUin = "";

            interpreter.set("context", new QFunSmartContext(mContext));
            interpreter.set("myUin", myUin);
            interpreter.set("classLoader", mContext.getClassLoader());
            interpreter.set("pluginPath", mPluginDir.getAbsolutePath());
            interpreter.set("pluginId", mPluginId);
            interpreter.setClassLoader(loader);

            PluginMethod api = new PluginMethod(mContext, mPluginDir);
            api.setCompiler(this);
            interpreter.set("api", api);

            for (Method m : PluginMethod.class.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && !m.getName().contains("$")) {
                    try {
                        interpreter.getNameSpace().setMethod(new BshMethod(m, api));
                    } catch (Throwable ignored) {}
                }
            }

            // 1:1 对齐 QFun 原生 source 执行
            interpreter.source(scriptFile.getAbsolutePath());

            try {
                BshMethod initM = interpreter.getNameSpace().getMethod("init", new Class[0]);
                if (initM != null) {
                    initM.invoke(new Object[0], interpreter);
                }
            } catch (Throwable ignored) {}

            mIsRunning = true;
            PLog.i("Plugin", "[" + mPluginId + "] 原生 QFun 架构启动成功，注册 " + menuItems.size() + " 项入口 (" + (System.currentTimeMillis() - tStart) + "ms)");
            return true;
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            mLastError = realEx.getMessage() != null ? realEx.getMessage() : realEx.getClass().getSimpleName();
            logError("脚本启动异常:\n" + getStackTrace(realEx));
            stop();
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public synchronized void stop() {
        if (!mIsRunning && interpreter == null) return;
        mIsRunning = false;
        try {
            if (interpreter != null) {
                BshMethod unLoadM = interpreter.getNameSpace().getMethod("unLoadPlugin", new Class[0]);
                if (unLoadM != null) {
                    unLoadM.invoke(new Object[0], interpreter);
                }
                interpreter.getNameSpace().clear();
            }
        } catch (Throwable ignored) {}
        menuItems.clear();
        sAllMsgMenuItems.entrySet().removeIf(e -> mPluginId.equals(e.getValue().pluginId));
        interpreter = null;
    }

    public void chatInterface(int cType, String peerUin, String name) {
        invokeScriptMethod("chatInterface", new Class[]{int.class, String.class, String.class}, new Object[]{cType, peerUin, name});
    }

    public void onPaiYiPai(String peerUin, int chatType, String opUin) {
        invokeScriptMethod("onPai", new Class[]{String.class, int.class, String.class}, new Object[]{peerUin, chatType, opUin});
        invokeScriptMethod("onPaiYiPai", new Class[]{String.class, int.class, String.class}, new Object[]{peerUin, chatType, opUin});
    }

    public void shutUpGroup(String troopUin, String memberUin, long time, String opUin) {
        invokeScriptMethod("shutUpGroup", new Class[]{String.class, String.class, long.class, String.class}, new Object[]{troopUin, memberUin, time, opUin});
    }

    public void joinGroup(String troopUin, String memberUin) {
        invokeScriptMethod("joinGroup", new Class[]{String.class, String.class}, new Object[]{troopUin, memberUin});
    }

    public void quitGroup(String troopUin, String memberUin) {
        invokeScriptMethod("quitGroup", new Class[]{String.class, String.class}, new Object[]{troopUin, memberUin});
    }

    public void invokeMsgMenuItem(String callback, Object msgData) {
        invokeScriptMethod(callback, new Class[]{Object.class}, new Object[]{msgData});
    }

    private void invokeScriptMethod(String methodName, Class<?>[] types, Object[] args) {
        if (!mIsRunning || interpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            BshMethod m = interpreter.getNameSpace().getMethod(methodName, types);
            if (m == null) {
                Class<?>[] altTypes = types.clone();
                for (int i = 0; i < altTypes.length; i++) {
                    if (altTypes[i] == int.class) altTypes[i] = Integer.class;
                }
                m = interpreter.getNameSpace().getMethod(methodName, altTypes);
            }
            if (m != null) {
                m.invoke(args, interpreter);
            }
        } catch (Throwable ignored) {}
        finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public void invokeMenuItem(String callback, int cType, String peerUin, String name) {
        if (!mIsRunning || interpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            BshMethod targetMethod = null;
            Object[] invokeArgs = null;

            for (BshMethod m : interpreter.getNameSpace().getMethods()) {
                if (m.getName().equals(callback)) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 3) {
                        targetMethod = m;
                        invokeArgs = new Object[]{cType, peerUin, name};
                        break;
                    } else if (pts.length == 4) {
                        targetMethod = m;
                        Contact contact = MsgSender.makeContact(peerUin, cType);
                        invokeArgs = new Object[]{cType, peerUin, name, contact};
                        break;
                    } else if (pts.length == 0) {
                        targetMethod = m;
                        invokeArgs = new Object[0];
                        break;
                    }
                }
            }

            if (targetMethod != null) {
                targetMethod.invoke(invokeArgs, interpreter);
            }
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("执行动作 [" + callback + "] 异常:\n" + getStackTrace(realEx));
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public void onMsg(Object msgData) {
        invokeScriptMethod("onMsg", new Class[]{Object.class}, new Object[]{msgData});
    }

    public String getMsg(String original) {
        if (!mIsRunning || interpreter == null || original == null) return original;
        try {
            BshMethod m = interpreter.getNameSpace().getMethod("getMsg", new Class[]{String.class});
            if (m != null) {
                Object res = m.invoke(new Object[]{original}, interpreter);
                if (res instanceof String) return (String) res;
            }
        } catch (Throwable ignored) {}
        return original;
    }

    private void logError(String text) {
        PLog.e("Plugin", "[" + mPluginId + "] " + text);
        PluginMethod errorLog = new PluginMethod(mContext, mPluginDir);
        errorLog.log("error.log", text);
    }

    private String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    public static class QFunSmartContext extends ContextWrapper {
        public static final String WINDOW_SERVICE = Context.WINDOW_SERVICE;
        public static final String INPUT_METHOD_SERVICE = Context.INPUT_METHOD_SERVICE;
        public static final String AUDIO_SERVICE = Context.AUDIO_SERVICE;
        public static final String CLIPBOARD_SERVICE = Context.CLIPBOARD_SERVICE;
        public static final String VIBRATOR_SERVICE = Context.VIBRATOR_SERVICE;
        public static final String CONNECTIVITY_SERVICE = Context.CONNECTIVITY_SERVICE;

        public QFunSmartContext(Context base) {
            super(base);
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.WINDOW_SERVICE.equals(name)) {
                Activity act = com.tencent.qqnt.patch.AppContext.getCurrentActivity();
                if (act != null) {
                    try {
                        return act.getWindowManager();
                    } catch (Throwable ignored) {}
                }
            }
            Activity act = com.tencent.qqnt.patch.AppContext.getCurrentActivity();
            if (act != null) {
                try {
                    Object svc = act.getSystemService(name);
                    if (svc != null) return svc;
                } catch (Throwable ignored) {}
            }
            return super.getSystemService(name);
        }
    }
}
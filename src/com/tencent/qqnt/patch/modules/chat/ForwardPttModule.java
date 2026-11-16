package com.tencent.qqnt.patch.modules.chat;

import android.content.Context;
import com.tencent.mobileqq.aio.msg.AIOMsgItem;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ForwardPttModule implements IPatchModule {

    private static final String TAG = "ForwardPtt";
    private static final int MSG_TYPE_PTT = 6;
    private static final int MSG_TYPE_TEXT = 2;
    private static final int INVALID_TYPE = 114514;
    private static final long PENDING_TIMEOUT_MS = 180000L;

    private static final Object sPendingLock = new Object();
    private static volatile AIOMsgItem sPendingPttItem = null;
    private static volatile long sPendingTimestamp = 0L;
    private static volatile Object sCachedSuccessErrorCode = null;

    @Override
    public String getId() {
        return "forward_ptt";
    }

    @Override
    public String getName() {
        return "语音转发";
    }

    @Override
    public String getCategory() {
        return CATEGORY_CHAT;
    }

    @Override
    public String getSubName() {
        return "长按语音气泡在 2X 旁增加转发按钮";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("forward_ptt", false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List injectPttMenu(List menuList, Object component, String forwardItemClassName) {
        if (!isModuleActive() || menuList == null || component == null) {
            return menuList;
        }

        try {
            for (Object item : menuList) {
                if (item != null && "ForwardMenuItem".equals(extractMenuItemIdentifier(item))) {
                    return menuList;
                }
            }

            Constructor<?> ctor = resolveForwardMenuItemCtor(component, forwardItemClassName);
            if (ctor == null) {
                PLog.w(TAG, "未定位到 ForwardMenuItem 构造器，放弃注入");
                return menuList;
            }

            Context context = resolveComponentContext(component);
            AIOMsgItem msgItem = resolveComponentMsgItem(component);
            if (context == null || msgItem == null) {
                return menuList;
            }

            Object menuItem = ctor.newInstance(context, msgItem, component, null);
            if (menuItem == null) {
                return menuList;
            }

            int targetIndex = -1;
            for (int i = 0; i < menuList.size(); i++) {
                if (isSpeedMenuItem(menuList.get(i))) {
                    targetIndex = i + 1;
                    break;
                }
            }
            if (targetIndex == -1) {
                for (int i = 0; i < menuList.size(); i++) {
                    if (isSpeechToTextMenuItem(menuList.get(i))) {
                        targetIndex = i + 1;
                        break;
                    }
                }
            }
            if (targetIndex == -1) {
                targetIndex = Math.min(2, menuList.size());
            }

            menuList.add(targetIndex, menuItem);
            PLog.i(TAG, "已成功在自然位置 (index " + targetIndex + ") 注入语音转发选项");

        } catch (Throwable t) {
            PLog.e(TAG, "injectPttMenu 异常", t);
        }

        return menuList;
    }

    public static void onStartForwardMsg(Object aioMsgItemObj) {
        if (!isModuleActive() || !(aioMsgItemObj instanceof AIOMsgItem)) {
            return;
        }

        try {
            AIOMsgItem aioMsgItem = (AIOMsgItem) aioMsgItemObj;
            MsgRecord record = aioMsgItem.getMsgRecord();
            if (record == null) return;

            synchronized (sPendingLock) {
                if (record.msgType == MSG_TYPE_PTT) {
                    record.msgType = MSG_TYPE_TEXT;
                    sPendingPttItem = aioMsgItem;
                    sPendingTimestamp = System.currentTimeMillis();
                    PLog.i(TAG, "捕获语音转发请求，临时伪装放行面板 (msgId=" + record.msgId + ")");
                } else {
                    sPendingPttItem = null;
                    sPendingTimestamp = 0L;
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "onStartForwardMsg 异常", t);
        }
    }

    public static void onEndStartForwardMsg(boolean started) {
        try {
            synchronized (sPendingLock) {
                if (sPendingPttItem != null) {
                    MsgRecord record = sPendingPttItem.getMsgRecord();
                    if (record != null && record.msgType == MSG_TYPE_TEXT) {
                        record.msgType = MSG_TYPE_PTT;
                    }
                    if (!started) {
                        sPendingPttItem = null;
                        sPendingTimestamp = 0L;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static Object onHandleAIOForward(Object allDataObj, Object targetContactsObj, String comment) {
        if (!isModuleActive()) {
            return null;
        }

        final AIOMsgItem targetItem;
        synchronized (sPendingLock) {
            if (sPendingPttItem == null) {
                return null;
            }
            if (System.currentTimeMillis() - sPendingTimestamp > PENDING_TIMEOUT_MS) {
                sPendingPttItem = null;
                sPendingTimestamp = 0L;
                return null;
            }
            MsgRecord checkRec = sPendingPttItem.getMsgRecord();
            long targetMsgId = checkRec != null ? checkRec.msgId : 0L;
            if (!isCompatibleForwardData(allDataObj, targetMsgId)) {
                sPendingPttItem = null;
                sPendingTimestamp = 0L;
                return null;
            }
            targetItem = sPendingPttItem;
            sPendingPttItem = null;
            sPendingTimestamp = 0L;
        }

        if (!(targetContactsObj instanceof List)) {
            return null;
        }

        try {
            final MsgRecord record = targetItem.getMsgRecord();
            if (record == null || record.elements == null || record.elements.isEmpty()) {
                return null;
            }

            record.msgType = MSG_TYPE_PTT;

            final List<?> contactList = (List<?>) targetContactsObj;
            if (contactList.isEmpty()) {
                return null;
            }

            final ArrayList<MsgElement> elements = new ArrayList<>(record.elements);
            final List<DestTarget> destList = new ArrayList<>();
            for (Object contactItem : contactList) {
                if (contactItem == null) continue;
                DestTarget dt = extractTarget(contactItem);
                if (dt != null) {
                    destList.add(dt);
                }
            }

            if (destList.isEmpty()) {
                return null;
            }

            Object successCode = resolveForwardSuccessCode(targetContactsObj.getClass().getClassLoader());
            if (successCode == null) {
                for (Object contactItem : contactList) {
                    neutralizeContactFallback(contactItem);
                }
            }

            PLog.i(TAG, "拦截语音转发成功，正在向 " + destList.size() + " 个目标分发语音");

            new Thread(() -> {
                for (DestTarget dest : destList) {
                    try {
                        Thread.sleep(350);
                        Contact contact = buildContact(dest);
                        MsgSender.sendRawElements(contact, elements);
                        PLog.i(TAG, "已成功发送语音至目标: " + dest.peerUin + " (type=" + dest.peerType + ")");

                        if (comment != null && !comment.trim().isEmpty()) {
                            MsgSender.sendMsg(contact, comment.trim());
                        }
                    } catch (Throwable t) {
                        PLog.e(TAG, "向目标 [" + dest.peerUin + "] 发送语音失败", t);
                    }
                }
            }).start();

            return successCode;
        } catch (Throwable t) {
            PLog.e(TAG, "onHandleAIOForward 异常", t);
            return null;
        }
    }

    private static boolean isCompatibleForwardData(Object allDataObj, long targetMsgId) {
        if (!(allDataObj instanceof Map)) {
            return true;
        }
        Map<?, ?> map = (Map<?, ?>) allDataObj;
        if (map.isEmpty()) {
            return false;
        }

        boolean hasMatchedType = false;
        for (Object val : map.values()) {
            if (val == null) continue;
            try {
                Method getTypeM = val.getClass().getMethod("getDataType");
                Object dataType = getTypeM.invoke(val);
                if (dataType != null) {
                    String name = dataType.toString();
                    if ("MIX_MSG".equals(name) || "TEXT".equals(name)) {
                        hasMatchedType = true;
                        Long embeddedMsgId = tryExtractMsgId(val, 0);
                        if (embeddedMsgId != null && embeddedMsgId > 0 && targetMsgId > 0 && embeddedMsgId != targetMsgId) {
                            return false;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return hasMatchedType;
    }

    private static Long tryExtractMsgId(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (f.getType() == long.class && "msgId".equalsIgnoreCase(f.getName())) {
                        return f.getLong(obj);
                    }
                    if (!f.getType().isPrimitive() && f.getType().getName().contains("ShareData")) {
                        Long nested = tryExtractMsgId(f.get(obj), depth + 1);
                        if (nested != null) return nested;
                    }
                } catch (Throwable ignored) {}
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveForwardSuccessCode(ClassLoader cl) {
        if (sCachedSuccessErrorCode != null) {
            return sCachedSuccessErrorCode;
        }
        try {
            ClassLoader loader = cl != null ? cl : AppContext.get().getClassLoader();
            Class<?> codeClz = Class.forName(
                    "com.tencent.mobileqq.sharepanel.forward.api.ForwardErrorCode", false, loader
            );
            Object val = Enum.valueOf((Class<Enum>) codeClz, "SUCCESS");
            sCachedSuccessErrorCode = val;
            return val;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Contact buildContact(DestTarget dest) {
        if (dest.peerUid != null && !dest.peerUid.isEmpty()
                && (dest.peerType != 2 || dest.peerUid.matches("\\d+"))) {
            return new Contact(dest.peerType, dest.peerUid, "");
        }
        return MsgSender.makeContact(dest.peerUin, dest.peerType);
    }

    private static class DestTarget {
        String peerUin;
        String peerUid;
        int peerType;
        DestTarget(String uin, String uid, int type) {
            this.peerUin = uin;
            this.peerUid = uid;
            this.peerType = type;
        }
    }

    private static DestTarget extractTarget(Object contactItem) {
        try {
            Class<?> clz = contactItem.getClass();
            String uin = (String) clz.getMethod("getPeerUin").invoke(contactItem);
            int type = (Integer) clz.getMethod("getPeerType").invoke(contactItem);
            String uid = "";
            try {
                Object uidObj = clz.getMethod("getPeerUid").invoke(contactItem);
                if (uidObj instanceof String) {
                    uid = (String) uidObj;
                }
            } catch (Throwable ignored) {}

            if (((uin != null && !uin.isEmpty()) || !uid.isEmpty()) && type != INVALID_TYPE) {
                return new DestTarget(uin != null ? uin : "", uid, type);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void neutralizeContactFallback(Object contactItem) {
        if (contactItem == null) return;
        try {
            Class<?> clz = contactItem.getClass();
            int type = (Integer) clz.getMethod("getPeerType").invoke(contactItem);
            Class<?> cur = clz;
            while (cur != null && cur != Object.class) {
                try {
                    Field f = cur.getDeclaredField("peerType");
                    if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        f.setInt(contactItem, INVALID_TYPE);
                        return;
                    }
                } catch (NoSuchFieldException ignored) {}
                cur = cur.getSuperclass();
            }
            for (Field f : clz.getDeclaredFields()) {
                if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    if (f.getInt(contactItem) == type) {
                        f.setInt(contactItem, INVALID_TYPE);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isSpeedMenuItem(Object item) {
        if (item == null) return false;
        String text = extractMenuItemTitle(item);
        if (text != null && !text.isEmpty()) {
            return text.contains("倍速") || text.contains("2X") || text.contains("2x")
                    || text.contains("1X") || text.contains("1x") || text.contains("1.5X");
        }
        return false;
    }

    private static boolean isSpeechToTextMenuItem(Object item) {
        if (item == null) return false;
        String text = extractMenuItemTitle(item);
        if (text != null && !text.isEmpty()) {
            return text.contains("转文字") || text.contains("隐藏文字");
        }
        return false;
    }

    private static String extractMenuItemTitle(Object item) {
        if (item == null) return "";
        Class<?> clz = item.getClass();

        for (Method m : clz.getMethods()) {
            if (m.getParameterTypes().length == 0 && CharSequence.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Object res = m.invoke(item);
                    if (res != null) {
                        String s = res.toString().trim();
                        if (!s.isEmpty() && !s.contains("MenuItem")) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        for (Field f : clz.getDeclaredFields()) {
            if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(item);
                    if (val != null) return val.toString();
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    private static String extractMenuItemIdentifier(Object item) {
        if (item == null) return "";
        Class<?> clz = item.getClass();
        for (Method m : clz.getMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class) {
                try {
                    m.setAccessible(true);
                    Object res = m.invoke(item);
                    if (res != null) {
                        String s = res.toString();
                        if (s.endsWith("MenuItem")) return s;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    private static synchronized Constructor<?> resolveForwardMenuItemCtor(Object component, String targetClassName) {
        try {
            ClassLoader cl = component.getClass().getClassLoader();
            if (cl == null) cl = AppContext.get().getClassLoader();

            Class<?> targetClz = null;
            if (targetClassName != null && !targetClassName.isEmpty()) {
                try {
                    targetClz = cl.loadClass(targetClassName);
                } catch (Throwable ignored) {}
            }

            if (targetClz == null) {
                String[] fallbacks = new String[]{
                        "com.tencent.qqnt.aio.menu.o",
                        "com.tencent.qqnt.aio.menu.j",
                        "com.tencent.qqnt.aio.menu.k",
                        "com.tencent.qqnt.aio.menu.l",
                        "com.tencent.qqnt.aio.menu.n"
                };
                for (String fb : fallbacks) {
                    try {
                        targetClz = cl.loadClass(fb);
                        if (targetClz != null) break;
                    } catch (Throwable ignored) {}
                }
            }

            if (targetClz != null) {
                for (Constructor<?> c : targetClz.getConstructors()) {
                    if (c.getParameterTypes().length == 4) {
                        c.setAccessible(true);
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Context resolveComponentContext(Object component) {
        try {
            Method getCtx = component.getClass().getMethod("getContext");
            Object ctx = getCtx.invoke(component);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {}
        return AppContext.getCurrentActivity();
    }

    private static AIOMsgItem resolveComponentMsgItem(Object component) {
        String[] candidateMethods = new String[]{"f2", "a2", "getMsgItem"};
        for (String mName : candidateMethods) {
            try {
                Method m = component.getClass().getMethod(mName);
                Object item = m.invoke(component);
                if (item instanceof AIOMsgItem) return (AIOMsgItem) item;
            } catch (Throwable ignored) {}
        }

        Class<?> cur = component.getClass();
        while (cur != null && cur != Object.class) {
            for (Field f : cur.getDeclaredFields()) {
                if (AIOMsgItem.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(component);
                        if (val instanceof AIOMsgItem) return (AIOMsgItem) val;
                    } catch (Throwable ignored) {}
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
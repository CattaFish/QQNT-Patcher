package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;

import com.tencent.mobileqq.aio.msg.AIOMsgItem;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DoubleTapRepeatModule implements IPatchModule {

    private static final String TAG = "RepeatMsg";

    public static final String KEY_REPEAT_MODE = "zzz_repeat_mode";
    public static final int MODE_SWIPE = 0;    // 方案一：左滑复读 (默认)
    public static final int MODE_PLUS_ONE = 1; // 方案二：强制显示 +1 图标

    private static volatile long sLastRepeatTime = 0L;

    @Override public String getId() { return "double_tap_repeat"; }
    @Override public String getName() { return "消息复读"; }
    @Override public String getCategory() { return CATEGORY_CHAT; }
    @Override public String getSubName() {
        if (!isEnabled()) {
            return "支持左滑消息或气泡旁 +1 图标复读";
        }
        int mode = getRepeatMode();
        return (mode == MODE_SWIPE ? "方案一：左滑消息复读" : "方案二：气泡旁显示 +1 图标");
    }
    @Override public boolean defaultEnabled() { return false; }
    @Override public boolean hasConfig() { return true; }

    public static int getRepeatMode() {
        String val = ConfigManager.getString(KEY_REPEAT_MODE, String.valueOf(MODE_SWIPE));
        try {
            return Integer.parseInt(val.trim());
        } catch (Throwable ignored) {
            return MODE_SWIPE;
        }
    }

    public static void setRepeatMode(int mode) {
        ConfigManager.setString(KEY_REPEAT_MODE, String.valueOf(mode));
    }

    public static boolean isSwipeModeActive() {
        return ConfigManager.isModuleEnabled("double_tap_repeat", false) && (getRepeatMode() == MODE_SWIPE);
    }

    public static boolean isPlusOneModeActive() {
        return ConfigManager.isModuleEnabled("double_tap_repeat", false) && (getRepeatMode() == MODE_PLUS_ONE);
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 消息复读配置", "配置", true, false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    private static int getOfficialSwipeThreshold() {
        try {
            Class<?> constClz = Class.forName("com.tencent.mobileqq.aio.msglist.holder.component.leftswipearea.LeftSwipeConstants");
            Object inst = null;
            for (Field f : constClz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == constClz) {
                    f.setAccessible(true);
                    inst = f.get(null);
                    break;
                }
            }

            if (inst != null) {
                for (Method m : constClz.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class) {
                        m.setAccessible(true);
                        return (Integer) m.invoke(inst);
                    }
                }
            }
        } catch (Throwable ignored) {}

        Context ctx = AppContext.get();
        if (ctx != null) {
            float density = ctx.getResources().getDisplayMetrics().density;
            return (int) (40f * density + 0.5f);
        }
        return 80;
    }

    public static boolean onLeftSwipeTrigger(Object componentObj, int scrollX) {
        if (!isSwipeModeActive() || componentObj == null) {
            return false;
        }

        int officialThreshold = getOfficialSwipeThreshold();
        if (scrollX <= officialThreshold) {
            return false;
        }

        try {
            Object msgItem = resolveMsgItemFromComponent(componentObj);
            if (msgItem != null) {
                performRepeat(msgItem);
                return true;
            }
        } catch (Throwable t) {
            PLog.e(TAG, "左滑复读异常", t);
        }

        return false;
    }

    public static boolean onFollowIconClick(Object listenerObj) {
        if (!isPlusOneModeActive() || listenerObj == null) {
            return false;
        }

        try {
            Object component = getFieldByTypeRecursive(listenerObj, "AIOMsgFollowComponent");
            if (component != null) {
                Object msgItem = resolveMsgItemFromComponent(component);
                if (msgItem != null) {
                    performRepeat(msgItem);
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "onFollowIconClick 异常", t);
        }

        return false;
    }

    private static Object resolveMsgItemFromComponent(Object componentObj) {
        if (componentObj == null) return null;

        Object direct = getFieldRecursive(componentObj, "i");
        if (direct != null && extractMsgRecord(direct) != null) return direct;
        direct = getFieldRecursive(componentObj, "h");
        if (direct != null && extractMsgRecord(direct) != null) return direct;

        Class<?> clz = componentObj.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(componentObj);
                    if (val != null && extractMsgRecord(val) != null) {
                        return val;
                    }
                } catch (Throwable ignored) {}
            }
            clz = clz.getSuperclass();
        }

        return invokeMethodReturningType(componentObj, "AIOMsgItem");
    }

    public static boolean performRepeat(Object msgItemOrRecord) {
        if (msgItemOrRecord == null) return false;

        long now = SystemClock.uptimeMillis();
        if (now - sLastRepeatTime < 450L) {
            return true;
        }
        sLastRepeatTime = now;

        MsgRecord record = extractMsgRecord(msgItemOrRecord);
        if (record == null) return false;

        try {
            String targetPeer = (record.peerUid != null && !record.peerUid.isEmpty())
                    ? record.peerUid
                    : String.valueOf(record.peerUin);
            Contact contact = new Contact(record.chatType, targetPeer, "");

            if (isNeedForward(record)) {
                boolean sent = forwardSend(contact, record);
                if (sent) return true;
            }

            return directSend(contact, record);
        } catch (Throwable t) {
            PLog.e(TAG, "performRepeat 异常", t);
        }
        return false;
    }

    private static MsgRecord extractMsgRecord(Object obj) {
        if (obj == null) return null;
        if (obj instanceof MsgRecord) return (MsgRecord) obj;

        if (obj instanceof AIOMsgItem) {
            AIOMsgItem item = (AIOMsgItem) obj;
            if (item.getMsgRecord() != null) return item.getMsgRecord();
            if (item.msgRecord != null) return item.msgRecord;
        }

        try {
            Method m = obj.getClass().getMethod("getMsgRecord");
            Object res = m.invoke(obj);
            if (res instanceof MsgRecord) return (MsgRecord) res;
        } catch (Throwable ignored) {}

        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                if (MsgRecord.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object res = f.get(obj);
                        if (res instanceof MsgRecord) return (MsgRecord) res;
                    } catch (Throwable ignored) {}
                }
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    private static boolean isNeedForward(MsgRecord record) {
        int type = record.msgType;
        if (type == 3 || type == 7) return true;
        if (type == 2) {
            if (hasRichMediaElement(record)) return true;
            return !hasAtElement(record);
        }
        return false;
    }

    private static boolean hasRichMediaElement(MsgRecord record) {
        if (record.elements == null) return false;
        for (MsgElement elem : record.elements) {
            if (elem == null) continue;
            if (elem.picElement != null || elem.marketFaceElement != null || elem.fileElement != null || elem.elementType == 5) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAtElement(MsgRecord record) {
        if (record.elements == null) return false;
        for (MsgElement elem : record.elements) {
            if (elem != null && elem.textElement != null && elem.textElement.atType == 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean forwardSend(Contact contact, MsgRecord record) {
        try {
            IKernelMsgService msgService = getNativeKernelMsgService();
            if (msgService == null) return false;

            ArrayList<Long> msgIds = new ArrayList<>();
            msgIds.add(record.msgId);

            ArrayList<Contact> dstContacts = new ArrayList<>();
            dstContacts.add(contact);

            Object msgAttrs = getFieldByTypeRecursive(record, "HashMap");

            for (Method m : msgService.getClass().getMethods()) {
                if ("forwardMsg".equals(m.getName()) && m.getParameterTypes().length == 5) {
                    m.setAccessible(true);
                    m.invoke(msgService, msgIds, contact, dstContacts, msgAttrs, null);
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "forwardSend 异常", t);
        }
        return false;
    }

    private static boolean directSend(Contact contact, MsgRecord record) {
        try {
            IKernelMsgService msgService = getNativeKernelMsgService();
            if (msgService != null) {
                long msgId = 0L;
                try {
                    Method genMethod = msgService.getClass().getMethod("generateMsgUniqueId", int.class);
                    Object res = genMethod.invoke(msgService, contact.getChatType());
                    if (res instanceof Long) msgId = (Long) res;
                } catch (Throwable ignored) {}

                Object msgAttrs = getFieldByTypeRecursive(record, "HashMap");

                for (Method m : msgService.getClass().getMethods()) {
                    if ("sendMsg".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                        m.setAccessible(true);
                        if (m.getParameterTypes().length == 5) {
                            m.invoke(msgService, msgId, contact, record.elements, msgAttrs, null);
                        } else {
                            m.invoke(msgService, msgId, contact, record.elements, msgAttrs);
                        }
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        MsgSender.sendRawElements(contact, record.elements);
        return true;
    }

    private static IKernelMsgService getNativeKernelMsgService() {
        IKernelMsgService service = MsgSender.getMsgService();
        if (service != null) return service;
        try {
            Class<?> fClz = Class.forName("com.tencent.qqnt.msg.f");
            Object session = fClz.getMethod("j").invoke(null);
            if (session instanceof IQQNTWrapperSession) {
                return ((IQQNTWrapperSession) session).getMsgService();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;

        final int[] selected = new int[]{ getRepeatMode() };

        SettingDialogBuilder.build(activity, "消息复读设置")
                .addRadioCards(Arrays.asList(
                        new SettingDialogBuilder.OptionCard("方案一：左滑消息复读", "向左滑动任意消息复读发送，替代消息回复"),
                        new SettingDialogBuilder.OptionCard("方案二：气泡旁显示 +1 图标", "在每条消息气泡旁边显示 +1 复读图标")
                ), selected[0], idx -> selected[0] = idx)
                .setPositiveButton("保存并应用", dialog -> {
                    setRepeatMode(selected[0]);
                    setEnabled(true);
                    dialog.dismiss();
                    ToastHelper.show(activity, "已保存消息复读配置");
                    if (onSaved != null) onSaved.run();
                })
                .show();
    }

    private static Object getFieldRecursive(Object obj, String fieldName) {
        if (obj == null) return null;
        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            try {
                Field f = clz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {}
            catch (Throwable ignored) {}
            clz = clz.getSuperclass();
        }
        return null;
    }

    private static Object getFieldByTypeRecursive(Object obj, String simpleTypeName) {
        if (obj == null) return null;
        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                if (f.getType().getName().contains(simpleTypeName)) {
                    try {
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (Throwable ignored) {}
                }
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    private static Object invokeMethodReturningType(Object obj, String simpleReturnTypeName) {
        if (obj == null) return null;
        Class<?> clz = obj.getClass();
        for (Method m : clz.getDeclaredMethods()) {
            if (m.getReturnType().getName().contains(simpleReturnTypeName)) {
                try {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 0) {
                        return m.invoke(obj);
                    } else if (m.getParameterTypes().length == 1 && Modifier.isStatic(m.getModifiers())
                            && m.getParameterTypes()[0] == clz) {
                        return m.invoke(null, obj);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}

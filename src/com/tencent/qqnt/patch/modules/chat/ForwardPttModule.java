package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
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
import java.util.ArrayList;
import java.util.List;

public class ForwardPttModule implements IPatchModule {

    private static final String TAG = "ForwardPtt";
    private static final int MSG_TYPE_PTT = 6;
    private static final int MSG_TYPE_TEXT = 2;
    private static final int INVALID_TYPE = 114514;

    // 缓存上一次触发语音转发的 AIOMsgItem
    private static volatile AIOMsgItem sPendingPttItem = null;
    private static volatile long sPendingTimestamp = 0L;

    // 缓存探测到的 ForwardMenuItem 构造器
    private static volatile Constructor<?> sForwardMenuItemCtor = null;
    private static volatile boolean sCtorResolved = false;

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
        return "语音长按菜单中出现转发按钮，支持私聊、群聊与多选";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 严格遵守默认关闭原则
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("forward_ptt", false);
    }

    /**
     * 【步骤 1：菜单构建点】
     * 在 AIOPttContentComponent.b2() 退出前拦截，将 ForwardMenuItem 插在 2X 倍速后面
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List injectPttMenu(List menuList, Object component) {
        if (!isModuleActive() || menuList == null || component == null) {
            return menuList;
        }

        try {
            // 1. 检查是否已经存在 ForwardMenuItem
            for (Object item : menuList) {
                if (item != null && "ForwardMenuItem".equals(getMenuItemIdentifier(item))) {
                    return menuList;
                }
            }

            // 2. 解析 ForwardMenuItem 构造器
            Constructor<?> ctor = resolveForwardMenuItemCtor(component);
            if (ctor == null) {
                PLog.w(TAG, "未定位到 ForwardMenuItem 构造器，放弃注入");
                return menuList;
            }

            // 3. 获取宿主上下文与消息项
            Context context = resolveComponentContext(component);
            AIOMsgItem msgItem = resolveComponentMsgItem(component);
            if (context == null || msgItem == null) {
                return menuList;
            }

            // 4. 反射创建 ForwardMenuItem(Context, msgItem, component, null)
            Object menuItem = ctor.newInstance(context, msgItem, component, null);
            if (menuItem == null) {
                return menuList;
            }

            // 5. 【核心自然排序】寻找“2X倍速”或“转文字”项，紧插其后
            int targetIndex = -1;

            // 优先查找 2X 倍速项
            for (int i = 0; i < menuList.size(); i++) {
                Object item = menuList.get(i);
                if (item == null) continue;
                String clsName = item.getClass().getName();
                if (clsName.contains(".aio.menu.ad") || isSpeedMenuItem(item)) {
                    targetIndex = i + 1;
                    break;
                }
            }

            // 若当前未播放/无倍速，查找“转文字”项
            if (targetIndex == -1) {
                for (int i = 0; i < menuList.size(); i++) {
                    Object item = menuList.get(i);
                    if (item == null) continue;
                    String clsName = item.getClass().getName();
                    if (clsName.contains(".aio.menu.w") || clsName.contains(".aio.menu.ac") || isSpeechToTextMenuItem(item)) {
                        targetIndex = i + 1;
                        break;
                    }
                }
            }

            // 兜底插入在操作区前面（index 2，在播放和基础项之后）
            if (targetIndex == -1) {
                targetIndex = Math.min(2, menuList.size());
            }

            menuList.add(targetIndex, menuItem);
            PLog.i(TAG, "已成功在原生自然位置 (index " + targetIndex + ") 注入语音转发选项");

        } catch (Throwable t) {
            PLog.e(TAG, "injectPttMenu 异常", t);
        }

        return menuList;
    }

    /**
     * 【步骤 2：转发启动放行点】
     * 在 NtMsgForwardUtils.startForwardMsgV2 前，将 msgType 临时伪装为文本
     */
    public static void onStartForwardMsg(Object aioMsgItemObj) {
        if (!isModuleActive() || !(aioMsgItemObj instanceof AIOMsgItem)) {
            return;
        }

        try {
            AIOMsgItem aioMsgItem = (AIOMsgItem) aioMsgItemObj;
            MsgRecord record = aioMsgItem.getMsgRecord();
            if (record == null) return;

            if (record.msgType == MSG_TYPE_PTT) {
                record.msgType = MSG_TYPE_TEXT; // 核心伪装：假冒为文本通过校验
                sPendingPttItem = aioMsgItem;
                sPendingTimestamp = System.currentTimeMillis();
                PLog.i(TAG, "已捕获语音转发请求，成功伪装为文本放行启动 (msgId=" + record.msgId + ")");
            }
        } catch (Throwable t) {
            PLog.e(TAG, "onStartForwardMsg 异常", t);
        }
    }

    /**
     * 【步骤 3：转发分发中枢】
     * 在 AIOForwardHandler.forward 入口拦截选人结果，破坏假文本发送并转调底层通道
     */
    public static void onHandleAIOForward(Object allDataObj, Object targetContactsObj, String comment) {
        if (!isModuleActive() || sPendingPttItem == null) {
            return;
        }

        // 超时保护（超过 3 分钟未转发则视为失效废弃）
        if (System.currentTimeMillis() - sPendingTimestamp > 180000L) {
            sPendingPttItem = null;
            return;
        }

        if (!(targetContactsObj instanceof List)) {
            return;
        }

        final AIOMsgItem targetItem = sPendingPttItem;
        sPendingPttItem = null;

        final List<?> contactList = (List<?>) targetContactsObj;
        if (contactList.isEmpty()) {
            return;
        }

        try {
            final MsgRecord record = targetItem.getMsgRecord();
            if (record == null || record.elements == null || record.elements.isEmpty()) {
                return;
            }

            // 1. 恢复原始类型，保护本地状态
            record.msgType = MSG_TYPE_PTT;

            // 2. 拷贝一份纯净的原始 elements（包含 PttElement）
            final ArrayList<MsgElement> elements = new ArrayList<>(record.elements);

            // 3. 解析所有选中的联系人目标，并破坏官方 contact 避免重复发送假文本
            final List<DestTarget> destList = new ArrayList<>();
            for (Object contactItem : contactList) {
                if (contactItem == null) continue;
                DestTarget dt = extractAndNeutralizeContact(contactItem);
                if (dt != null) {
                    destList.add(dt);
                }
            }

            if (destList.isEmpty()) {
                return;
            }

            PLog.i(TAG, "成功拦截语音转发，准备向 " + destList.size() + " 个目标分发语音...");

            // 4. 异步线程执行底层逐一发包
            new Thread(() -> {
                for (DestTarget dest : destList) {
                    try {
                        Thread.sleep(350); // 间隔防风控
                        Contact contact = MsgSender.makeContact(dest.peerUin, dest.peerType);
                        MsgSender.sendRawElements(contact, elements);
                        PLog.i(TAG, "已向目标 [" + dest.peerUin + "](type=" + dest.peerType + ") 发送语音");

                        // 如果附带了留言，顺带发送一条文字留言
                        if (comment != null && !comment.trim().isEmpty()) {
                            MsgSender.sendMsg(contact, comment.trim());
                        }
                    } catch (Throwable t) {
                        PLog.e(TAG, "发送语音给目标 [" + dest.peerUin + "] 失败", t);
                    }
                }
            }).start();

        } catch (Throwable t) {
            PLog.e(TAG, "onHandleAIOForward 异常", t);
        }
    }

    private static class DestTarget {
        String peerUin;
        int peerType;
        DestTarget(String uin, int type) { this.peerUin = uin; this.peerType = type; }
    }

    private static DestTarget extractAndNeutralizeContact(Object contactItem) {
        try {
            Class<?> clz = contactItem.getClass();
            Method getUinM = clz.getMethod("getPeerUin");
            Method getTypeM = clz.getMethod("getPeerType");

            String uin = (String) getUinM.invoke(contactItem);
            int type = (Integer) getTypeM.invoke(contactItem);

            // 核心破坏机制：将官方 contact 对象的 peerType 字段反射置为 INVALID_TYPE
            // 使得官方协程执行到 native 发送时因为非法 chatType 被直接忽略，绝不会发出垃圾假文本！
            for (Field f : clz.getDeclaredFields()) {
                if ("peerType".equals(f.getName()) || (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))) {
                    f.setAccessible(true);
                    if (f.getInt(contactItem) == type) {
                        f.setInt(contactItem, INVALID_TYPE);
                        break;
                    }
                }
            }

            if (uin != null && !uin.isEmpty() && type != INVALID_TYPE) {
                return new DestTarget(uin, type);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isSpeedMenuItem(Object item) {
        try {
            Method fMethod = item.getClass().getMethod("f");
            Object name = fMethod.invoke(item);
            if (name != null) {
                String s = name.toString();
                return s.contains("倍速") || s.contains("2X") || s.contains("2x") || s.contains("1X") || s.contains("1x");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isSpeechToTextMenuItem(Object item) {
        try {
            Method fMethod = item.getClass().getMethod("f");
            Object name = fMethod.invoke(item);
            if (name != null) {
                String s = name.toString();
                return s.contains("转文字") || s.contains("文字");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String getMenuItemIdentifier(Object item) {
        try {
            Method eMethod = item.getClass().getMethod("e");
            Object id = eMethod.invoke(item);
            return id != null ? id.toString() : "";
        } catch (Throwable ignored) {}
        return "";
    }

    private static synchronized Constructor<?> resolveForwardMenuItemCtor(Object component) {
        if (sCtorResolved) return sForwardMenuItemCtor;
        try {
            ClassLoader cl = component.getClass().getClassLoader();
            if (cl == null) cl = AppContext.get().getClassLoader();

            // 优先尝试标准类名 com.tencent.qqnt.aio.menu.j
            Class<?> targetClz = null;
            try {
                targetClz = cl.loadClass("com.tencent.qqnt.aio.menu.j");
            } catch (Throwable ignored) {}

            // 若类名混淆，遍历寻找 e() == "ForwardMenuItem" 的类
            if (targetClz == null) {
                for (char c = 'a'; c <= 'z'; c++) {
                    try {
                        Class<?> candidate = cl.loadClass("com.tencent.qqnt.aio.menu." + c);
                        for (Method m : candidate.getDeclaredMethods()) {
                            if ("e".equals(m.getName()) && m.getParameterTypes().length == 0 && m.getReturnType() == String.class) {
                                m.setAccessible(true);
                                // 静态探测
                                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                                    if ("ForwardMenuItem".equals(m.invoke(null))) { targetClz = candidate; break; }
                                }
                            }
                        }
                        if (targetClz != null) break;
                    } catch (Throwable ignored) {}
                }
            }

            if (targetClz != null) {
                for (Constructor<?> c : targetClz.getConstructors()) {
                    if (c.getParameterTypes().length == 4) {
                        c.setAccessible(true);
                        sForwardMenuItemCtor = c;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        sCtorResolved = true;
        return sForwardMenuItemCtor;
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
        try {
            Method getA2 = component.getClass().getMethod("a2");
            Object item = getA2.invoke(component);
            if (item instanceof AIOMsgItem) return (AIOMsgItem) item;
        } catch (Throwable ignored) {}

        // 反射查找父类中类型为 AIOMsgItem 的字段
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
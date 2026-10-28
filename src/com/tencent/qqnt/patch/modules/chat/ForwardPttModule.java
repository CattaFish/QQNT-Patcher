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
import java.util.ArrayList;
import java.util.List;

public class ForwardPttModule implements IPatchModule {

    private static final String TAG = "ForwardPtt";
    private static final int MSG_TYPE_PTT = 6;
    private static final int MSG_TYPE_TEXT = 2;
    private static final int INVALID_TYPE = 114514;

    private static volatile AIOMsgItem sPendingPttItem = null;
    private static volatile long sPendingTimestamp = 0L;

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
        return "长按语音气泡在 2X 旁增加转发按钮，支持私聊、群聊与多选";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("forward_ptt", false);
    }

    /**
     * 【步骤 1：菜单注入与自然排序】
     * 9.2.90 为 AIOPttContentComponent->b2()Ljava/util/List;
     * 9.3.75 为 AIOPttContentComponent->g2()Ljava/util/List;
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List injectPttMenu(List menuList, Object component, String forwardItemClassName) {
        if (!isModuleActive() || menuList == null || component == null) {
            return menuList;
        }

        try {
            // 避免重复注入
            for (Object item : menuList) {
                if (item != null && "ForwardMenuItem".equals(extractMenuItemIdentifier(item))) {
                    return menuList;
                }
            }

            // 1. 获取 ForwardMenuItem 类构造器
            // 9.2.90 为 com.tencent.qqnt.aio.menu.j
            // 9.3.75 为 com.tencent.qqnt.aio.menu.o
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

            // 2. 构造菜单实例：(Context, msgItem, component, null)
            Object menuItem = ctor.newInstance(context, msgItem, component, null);
            if (menuItem == null) {
                return menuList;
            }

            // 3. 【全动态定位】：寻找“2X倍速”或“转文字”的自然位置
            // 2X倍速项: 9.2.90 为 aio.menu.ad, 9.3.75 为 aio.menu.aj
            // 转文字项: 9.2.90 为 aio.menu.w/ac, 9.3.75 为 aio.menu.ac/ai
            int targetIndex = -1;

            // A. 优先查找 2X 倍速项
            for (int i = 0; i < menuList.size(); i++) {
                Object it = menuList.get(i);
                if (isSpeedMenuItem(it)) {
                    targetIndex = i + 1; // 放在 2X 后面！
                    break;
                }
            }

            // B. 若当前未播放/无倍速项，退而查找“转文字”项
            if (targetIndex == -1) {
                for (int i = 0; i < menuList.size(); i++) {
                    Object it = menuList.get(i);
                    if (isSpeechToTextMenuItem(it)) {
                        targetIndex = i + 1; // 放在转文字后面
                        break;
                    }
                }
            }

            // C. 兜底放置在 index 2
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

    /**
     * 【步骤 2：转发启动放行点】
     * 9.2.90 / 9.3.75 均为 NtMsgForwardUtils->t(...)
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
                record.msgType = MSG_TYPE_TEXT; // 假冒文本绕过 e 表检查
                sPendingPttItem = aioMsgItem;
                sPendingTimestamp = System.currentTimeMillis();
                PLog.i(TAG, "捕获语音转发请求，成功伪装为文本放行启动 (msgId=" + record.msgId + ")");
            }
        } catch (Throwable t) {
            PLog.e(TAG, "onStartForwardMsg 异常", t);
        }
    }

    /**
     * 【步骤 3：转发分发中枢】
     * 9.2.90 / 9.3.75 均为 AIOForwardHandler->forward(...)
     */
    public static void onHandleAIOForward(Object allDataObj, Object targetContactsObj, String comment) {
        if (!isModuleActive() || sPendingPttItem == null) {
            return;
        }

        // 超时保护（3 分钟内未转发则丢弃）
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

            // 1. 恢复原始语音类型
            record.msgType = MSG_TYPE_PTT;

            // 2. 提取并保留原始语音 elements
            final ArrayList<MsgElement> elements = new ArrayList<>(record.elements);

            // 3. 解析目标联系人，并将官方 contact 的 peerType 置为无效，避免重复发送假文本
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

            PLog.i(TAG, "拦截语音转发成功，正在向 " + destList.size() + " 个目标分发语音");

            // 4. 异步逐一底层发包
            new Thread(() -> {
                for (DestTarget dest : destList) {
                    try {
                        Thread.sleep(350); // 间隔防频控
                        Contact contact = MsgSender.makeContact(dest.peerUin, dest.peerType);
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

            // 破坏 peerType 使官方后续发包跳过该联系人
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

    /**
     * 全动态文案匹配：判断是否为 2X 倍速项 (不依赖类名是 ad 还是 aj)
     */
    private static boolean isSpeedMenuItem(Object item) {
        if (item == null) return false;
        String text = extractMenuItemTitle(item);
        if (text != null && !text.isEmpty()) {
            return text.contains("倍速") || text.contains("2X") || text.contains("2x")
                    || text.contains("1X") || text.contains("1x") || text.contains("1.5X");
        }
        return false;
    }

    /**
     * 全动态文案匹配：判断是否为转文字项 (不依赖类名是 w/ac 还是 ac/ai)
     */
    private static boolean isSpeechToTextMenuItem(Object item) {
        if (item == null) return false;
        String text = extractMenuItemTitle(item);
        if (text != null && !text.isEmpty()) {
            return text.contains("转文字") || text.contains("隐藏文字");
        }
        return false;
    }

    /**
     * 全动态反射获取菜单标题文字（9.2.90 为 f(), 9.3.75 为 h()）
     */
    private static String extractMenuItemTitle(Object item) {
        if (item == null) return "";
        Class<?> clz = item.getClass();

        // 1. 遍历所有无参返回 CharSequence/String 的方法
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

        // 2. 遍历自身声明的 String 属性
        for (Field f : clz.getDeclaredFields()) {
            if (f.getType() == String.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(item);
                    if (val != null) return val.toString();
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    /**
     * 全动态反射获取标识符（9.2.90 为 e(), 9.3.75 为 g()）
     */
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

            // 1. 优先使用 Python 规则直接嗅探到的类名 (9.2.90=j, 9.3.75=o)
            if (targetClassName != null && !targetClassName.isEmpty()) {
                try {
                    targetClz = cl.loadClass(targetClassName);
                } catch (Throwable ignored) {}
            }

            // 2. 兜底扫描 com.tencent.qqnt.aio.menu. 下的类
            if (targetClz == null) {
                String[] fallbacks = new String[]{
                        "com.tencent.qqnt.aio.menu.o", // 9.3.75
                        "com.tencent.qqnt.aio.menu.j", // 9.2.90
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
        // 9.2.90 为 a2(), 9.3.75 为 f2()
        String[] candidateMethods = new String[]{"f2", "a2", "getMsgItem"};
        for (String mName : candidateMethods) {
            try {
                Method m = component.getClass().getMethod(mName);
                Object item = m.invoke(component);
                if (item instanceof AIOMsgItem) return (AIOMsgItem) item;
            } catch (Throwable ignored) {}
        }

        // 递归扫描父类
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
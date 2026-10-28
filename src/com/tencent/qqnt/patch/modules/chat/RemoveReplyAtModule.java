package com.tencent.qqnt.patch.modules.chat;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Method;

public class RemoveReplyAtModule implements IPatchModule {

    private static final String TAG = "RemoveReplyAt";

    @Override
    public String getId() {
        return "remove_reply_at";
    }

    @Override
    public String getName() {
        return "去除回复自动艾特";
    }

    @Override
    public String getCategory() {
        return CATEGORY_CHAT;
    }

    @Override
    public String getSubName() {
        return "引用回复消息时不再向输入框自动添加艾特";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("remove_reply_at", false);
    }

    /**
     * 接管 AIOInputReply 中发出的路由意图事件
     * 若为 InsertAtMemberSpan 且功能开启，则彻底拦截丢弃；其余事件（如弹起键盘）正常放行
     */
    public static void dispatchRouteIntent(Object router, Object intent) {
        if (intent != null && intent.getClass().getName().contains("InsertAtMemberSpan")) {
            if (isModuleActive()) {
                PLog.i(TAG, "成功精准拦截回复艾特意图 (InsertAtMemberSpan)！输入框将保持纯净");
                return; // 直接丢弃，不向路由总线发射该事件！
            }
        }

        // 其余事件（如弹起键盘 ShowKeyboardMsgIntent）或未开启功能时，正常执行原版 router.h(intent)
        if (router != null && intent != null) {
            try {
                Method hMethod = null;
                for (Method m : router.getClass().getMethods()) {
                    if ("h".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        hMethod = m;
                        break;
                    }
                }
                if (hMethod != null) {
                    hMethod.setAccessible(true);
                    hMethod.invoke(router, intent);
                }
            } catch (Throwable t) {
                PLog.e(TAG, "分发路由意图异常", t);
            }
        }
    }
}
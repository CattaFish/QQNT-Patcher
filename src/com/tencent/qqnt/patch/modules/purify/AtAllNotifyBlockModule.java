package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Method;

public class AtAllNotifyBlockModule implements IPatchModule {
    private static final String TAG = "NotifyBlock";

    @Override public String getId() { return "block_at_all_notify"; }
    @Override public String getName() { return "静默 @全体 与群待办通知"; }
    @Override public String getCategory() { return CATEGORY_PURIFY; }
    @Override public String getSubName() { return "拦截群内 @全体成员 弹窗与 群待办 提醒"; }
    @Override public boolean defaultEnabled() { return false; }

    public static boolean shouldDropTroopToDo() {
        boolean enabled = ConfigManager.isModuleEnabled("block_at_all_notify", false);
        if (enabled) {
            PLog.i(TAG, "命中静默：拦截 0x135 群待办强提醒");
        }
        return enabled;
    }

    public static boolean shouldDropMsgNotify(Object msgNotifyItemObj) {
        if (msgNotifyItemObj == null) return false;
        if (!ConfigManager.isModuleEnabled("block_at_all_notify", false)) return false;
        try {
            Class<?> dClz = Class.forName("com.tencent.qqnt.notification.util.d");
            Object dInstance = dClz.getField("a").get(null);
            Method aMethod = dClz.getMethod("a", Class.forName("com.tencent.qqnt.kernel.nativeinterface.MsgNotifyItem"));
            Object recentContactInfo = aMethod.invoke(dInstance, msgNotifyItemObj);
            return shouldDropRecentContact(recentContactInfo);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldDropRecentContact(Object recentContactInfoObj) {
        if (recentContactInfoObj == null) return false;
        if (!ConfigManager.isModuleEnabled("block_at_all_notify", false)) return false;
        try {
            Class<?> clz = recentContactInfoObj.getClass();
            int chatType = clz.getField("chatType").getInt(recentContactInfoObj);
            int atType = clz.getField("atType").getInt(recentContactInfoObj);

            if (chatType == 2) {
                boolean isAtAll = (atType & 1) != 0;
                boolean isAtMe  = (atType & 4) != 0;

                if (isAtAll && !isAtMe) {
                    PLog.i(TAG, "命中静默：丢弃纯 @全体成员 实时通知 (atType=" + atType + ")");
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "判断失败", t);
        }
        return false;
    }
}

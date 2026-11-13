package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

public class BlockedTroopModule implements IPatchModule {

    private static final String TAG = "BlockedTroop";

    @Override
    public String getId() {
        return "blocked_troop"; // 与 rules 中的 RULE_ID 保持一致
    }

    @Override
    public String getName() {
        return "查看被封禁群聊";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY; // 归类在“净化”模块
    }

    @Override
    public String getSubName() {
        return "解除被封禁群聊无法进入查看资料的限制";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 严格遵循默认关闭原则
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("blocked_troop", false);
    }

    /**
     * 过滤 AIO 消息长按触发时的群封禁状态判定
     * 仅在原状态为被封禁且模块开启时放行，保留退群/解散等其他判定不受影响
     */
    public static boolean filterLongClickBlock(boolean isBlocked) {
        if (!isBlocked) {
            return false;
        }
        if (isModuleActive()) {
            PLog.d(TAG, "已放行被封禁群聊的消息长按菜单");
            return false;
        }
        return true;
    }
}
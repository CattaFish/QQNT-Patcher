package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class BlockedTroopModule implements IPatchModule {

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
}
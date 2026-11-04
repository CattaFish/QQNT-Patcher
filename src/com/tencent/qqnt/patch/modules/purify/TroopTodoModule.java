package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class TroopTodoModule implements IPatchModule {

    @Override
    public String getId() {
        return "troop_todo"; // 对应 rules/troop_todo_rules.py
    }

    @Override
    public String getName() {
        return "静默群待办强提醒";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public String getSubName() {
        return "过滤群聊群待办强提醒通知";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public int getOrder() {
        return 11; // 紧随 @全体成员 (10) 的正下方，绝不掉到底部
    }
}
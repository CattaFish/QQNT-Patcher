package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class AtAllNotifyBlockModule implements IPatchModule {

    @Override
    public String getId() {
        return "block_at_all";
    }

    @Override
    public String getName() {
        return "屏蔽群聊 @全体成员";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public String getSubName() {
        return "过滤群聊中 @全体成员 通知";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public int getOrder() {
        return 10; // 固定排在前面
    }
}
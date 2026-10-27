package com.tencent.qqnt.patch.modules.chat;

import com.tencent.qqnt.patch.IPatchModule;

public class UnlockSwipeReplyModule implements IPatchModule {

    @Override
    public String getId() {
        return "unlock_swipe_reply";
    }

    @Override
    public String getName() {
        return "解除左滑回复限制";
    }

    @Override
    public String getCategory() {
        return CATEGORY_CHAT;
    }

    @Override
    public String getSubName() {
        return "允许对所有类型的消息进行左滑回复";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }
}
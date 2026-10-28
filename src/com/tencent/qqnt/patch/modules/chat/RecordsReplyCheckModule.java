package com.tencent.qqnt.patch.modules.chat;

import com.tencent.qqnt.patch.IPatchModule;

public class RecordsReplyCheckModule implements IPatchModule {

    @Override
    public String getId() {
        return "records_reply_check";
    }

    @Override
    public String getName() {
        return "查看聊天记录回复图片";
    }

    @Override
    public String getCategory() {
        return CATEGORY_CHAT;
    }

    @Override
    public String getSubName() {
        return "强制查看合并转发或聊天记录回复中包含的图片消息";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 严格遵守默认关闭原则
    }
}
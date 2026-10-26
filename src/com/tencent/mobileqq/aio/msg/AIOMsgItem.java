package com.tencent.mobileqq.aio.msg;

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;

public class AIOMsgItem {
    public MsgRecord msgRecord;

    public AIOMsgItem() {}
    public AIOMsgItem(MsgRecord msgRecord) {
        this.msgRecord = msgRecord;
    }

    public MsgRecord getMsgRecord() {
        return msgRecord;
    }

    public long getMsgId() {
        return msgRecord != null ? msgRecord.msgId : 0L;
    }
}
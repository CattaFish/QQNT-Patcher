package com.tencent.qqnt.kernel.nativeinterface;

import java.util.ArrayList;

public final class QGroupImportantMsgNotifyReqBody {
    public long groupCode;
    public ArrayList<QGroupImportantMsgNotifySwitch> notifySwitches = new ArrayList<>();

    public QGroupImportantMsgNotifyReqBody() {}

    public long getGroupCode() { return groupCode; }
    public ArrayList<QGroupImportantMsgNotifySwitch> getNotifySwitches() { return notifySwitches; }
}
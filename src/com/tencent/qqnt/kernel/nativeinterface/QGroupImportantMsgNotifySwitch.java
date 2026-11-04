package com.tencent.qqnt.kernel.nativeinterface;

public final class QGroupImportantMsgNotifySwitch {
    public int notifyType;
    public int uint32Switch;
    public int localListOrder;
    public String localSwitchName = "";
    public boolean localUIShow;

    public QGroupImportantMsgNotifySwitch() {}

    public int getNotifyType() { return notifyType; }
    public int getUint32Switch() { return uint32Switch; }
    public int getLocalListOrder() { return localListOrder; }
    public String getLocalSwitchName() { return localSwitchName; }
    public boolean getLocalUIShow() { return localUIShow; }
}
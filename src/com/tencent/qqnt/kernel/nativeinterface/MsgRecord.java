package com.tencent.qqnt.kernel.nativeinterface;

import java.util.ArrayList;

public class MsgRecord {
    public int msgType;
    public int subMsgType;
    public int chatType;
    public int sendType;
    public long msgId;
    public long msgSeq;
    public long msgTime;
    public String peerUid;
    public long peerUin;
    public String senderUid;
    public long senderUin;
    public String sendMemberName;
    public String sendNickName;
    public String guildId;
    public String guildName;
    public ArrayList<MsgElement> elements;
    public byte[] extInfoForUI;
}

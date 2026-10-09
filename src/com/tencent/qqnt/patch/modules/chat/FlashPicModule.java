package com.tencent.qqnt.patch.modules.chat;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Field;
import java.util.List;

public class FlashPicModule implements IPatchModule {
    private static final String TAG = "FlashPic";

    @Override public String getId() { return "flash_pic"; }
    @Override public String getName() { return "闪照破解"; }

    @Override
    public void onRecvMsg(List<MsgRecord> msgList) {
        for (MsgRecord record : msgList) decryptSingleRecord(record);
    }

    @Override
    public void onAIOMsgItem(MsgRecord record) {
        decryptSingleRecord(record);
    }

    private void decryptSingleRecord(MsgRecord record) {
        if (record == null) return;
        try {
            boolean isFlash = false;
            if (record.subMsgType == 8194 || (record.subMsgType & 8192) != 0) {
                isFlash = true;
                record.subMsgType = 0;
                record.msgType = 2;
                record.extInfoForUI = null;
            }

            if (record.elements != null) {
                for (MsgElement elem : record.elements) {
                    if (elem == null) continue;
                    PicElement pic = elem.picElement;
                    if (pic != null) {
                        boolean matchPic = Boolean.TRUE.equals(pic.isFlashPic)
                                || pic.picSubType == 8194
                                || (pic.picSubType & 8192) != 0
                                || (pic.summary != null && pic.summary.contains("闪照"));

                        if (matchPic) {
                            isFlash = true;
                            pic.isFlashPic = Boolean.FALSE;
                            pic.picSubType = 0;
                            pic.summary = "[图片]";
                            elem.extBufForUI = null;
                            cleanEmojiProperties(pic);
                        }
                    }
                }
            }

            if (isFlash && record.msgId > 0) {
                PLog.once(TAG, record.msgId, "闪照气泡已转换为普通图片 (msgId=" + record.msgId + ", sendType=" + record.sendType + ")");
            }
        } catch (Throwable ignored) {}
    }

    private void cleanEmojiProperties(PicElement pic) {
        try {
            for (Field f : pic.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                String name = f.getName().toLowerCase();
                if (name.contains("emoji") || name.contains("zplan") || name.contains("mall")) {
                    if (!f.getType().isPrimitive()) f.set(pic, null);
                }
            }
        } catch (Throwable ignored) {}
    }
}

package com.tencent.qqnt.patch.modules.chat;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.util.PLog;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MeowModule implements IPatchModule {
    private static final String TAG = "Meow";

    @Override public String getId() { return "meow_helper"; }
    @Override public String getName() { return "喵喵助手"; }
    @Override public boolean defaultEnabled() { return false; }

    private static final Pattern TAIL_PAIR_OR_OPEN = Pattern.compile("^(.*?)([\\s。\\.！!？\\?~～…]*(?:[\\(（\\[【]\\s*[\\)）\\]】]|[\\(（\\[【])[\\s。\\.！!？\\?~～…]*|[\\s。\\.！!？\\?~～…]+)$");
    private static final Pattern TAIL_CLOSE = Pattern.compile("^(.*?)([\\s。\\.！!？\\?~～…]*[\\)）\\]】][\\s。\\.！!？\\?~～…]*)$");

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        for (MsgElement elem : elements) {
            if (elem == null) continue;
            if (elem.picElement != null) {
                PicElement pic = elem.picElement;
                PLog.d(TAG, "发包图片探测 -> isFlashPic=" + pic.isFlashPic + ", subType=" + pic.picSubType);
            }
            if (elem.textElement != null && elem.textElement.content != null && !elem.textElement.content.trim().isEmpty()) {
                elem.textElement.content = transformToMeow(elem.textElement.content);
            }
        }
    }

    private String transformToMeow(String original) {
        if (original == null || original.isEmpty()) return original;
        String text = original.replace("你", "主人").replace("我", "猫猫");

        Matcher m = TAIL_PAIR_OR_OPEN.matcher(text);
        if (m.matches()) return m.group(1) + (m.group(1).endsWith("喵") ? "" : "喵") + m.group(2);

        Matcher cm = TAIL_CLOSE.matcher(text);
        if (cm.matches()) {
            String b = cm.group(1);
            if (!b.contains("（") && !b.contains("(") && !b.contains("【") && !b.contains("[")) {
                return b + (b.endsWith("喵") ? "" : "喵") + cm.group(2);
            }
        }
        return (text.endsWith("喵") || text.endsWith("喵~")) ? text : text + "喵~";
    }
}

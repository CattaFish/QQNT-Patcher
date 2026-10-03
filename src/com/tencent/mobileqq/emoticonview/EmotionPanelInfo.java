package com.tencent.mobileqq.emoticonview;

import com.tencent.mobileqq.data.EmoticonPackage;

public class EmotionPanelInfo {
    public int type;
    public int columnNum;
    public EmoticonPackage emotionPkg;

    public EmotionPanelInfo(int type, int columnNum, EmoticonPackage emotionPkg) {
        this.type = type;
        this.columnNum = columnNum;
        this.emotionPkg = emotionPkg;
    }
}
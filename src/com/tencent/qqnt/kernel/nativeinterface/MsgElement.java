package com.tencent.qqnt.kernel.nativeinterface;

public class MsgElement {
    public int elementType;
    public TextElement textElement;
    public PicElement picElement;
    public Object videoElement;
    public FileElement fileElement;
    public MarketFaceElement marketFaceElement; // 补全大表情元素
    public byte[] extBufForUI;
}
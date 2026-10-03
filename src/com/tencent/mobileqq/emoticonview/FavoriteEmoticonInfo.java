package com.tencent.mobileqq.emoticonview;

import android.content.Context;
import android.graphics.drawable.Drawable;

public class FavoriteEmoticonInfo extends BaseFavoriteEmoticonInfo {
    public String actionData;
    public String emojiMd5;
    public int jumpId;
    public String remark;

    public FavoriteEmoticonInfo() {}

    public Drawable getZoomDrawable(Context context, float f, int w, int h) {
        return null;
    }
}
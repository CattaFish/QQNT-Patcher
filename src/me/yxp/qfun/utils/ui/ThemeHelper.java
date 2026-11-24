package me.yxp.qfun.utils.ui;

public class ThemeHelper {
    public static final ThemeHelper INSTANCE = new ThemeHelper();

    public boolean isNightMode() {
        return com.tencent.qqnt.patch.util.ThemeHelper.isNightMode();
    }
}
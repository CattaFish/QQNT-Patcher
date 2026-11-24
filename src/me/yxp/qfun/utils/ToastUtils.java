package me.yxp.qfun.utils;

import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.util.ToastHelper;

public class ToastUtils {
    public static final ToastUtils INSTANCE = new ToastUtils();

    public void show(String text) {
        ToastHelper.show(AppContext.get(), text);
    }
}

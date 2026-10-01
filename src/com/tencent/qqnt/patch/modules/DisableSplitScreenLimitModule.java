package com.tencent.qqnt.patch.modules;

import com.tencent.qqnt.patch.IPatchModule;

public class DisableSplitScreenLimitModule implements IPatchModule {

    @Override
    public String getId() {
        return "disable_split_screen_limit";
    }

    @Override
    public String getName() {
        return "伪装处于非多窗口模式";
    }

    @Override
    public String getSubName() {
        return "解除分屏状态下扫码等功能的使用限制";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }
}
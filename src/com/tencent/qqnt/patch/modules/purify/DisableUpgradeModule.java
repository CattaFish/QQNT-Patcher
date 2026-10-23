package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class DisableUpgradeModule implements IPatchModule {

    @Override
    public String getId() {
        return "disable_upgrade";
    }

    @Override
    public String getName() {
        return "禁止检查更新";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY; // 归类在“净化”模块
    }

    @Override
    public String getSubName() {
        return "去除版本检测、关于页更新提示与版本过低强制登出";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 默认关闭
    }
}
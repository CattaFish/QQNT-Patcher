package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class TabletModeModule implements IPatchModule {
    @Override public String getId() { return "tablet_mode"; }
    @Override public String getName() { return "强制平板模式 (需重启QQ)"; }
    @Override public String getCategory() { return CATEGORY_PURIFY; }
    @Override public boolean defaultEnabled() { return false; }
}

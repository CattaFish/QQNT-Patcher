package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.FeatureConfig;

public interface SettingItem {
    default String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }

    String getGroupName();
    String getTitle();
    Object createView(ClassLoader cl, Activity activity, Runnable onRefresh);

    default boolean isSearchable() { return true; }
    default boolean isPluginPage() { return false; }
    default String getFeatureId() { return ""; }

    default boolean isFeatureEnabled() {
        String fid = getFeatureId();
        return fid == null || fid.isEmpty() || FeatureConfig.has(fid);
    }
}

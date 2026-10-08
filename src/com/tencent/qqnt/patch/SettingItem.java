package com.tencent.qqnt.patch;

import android.app.Activity;

public interface SettingItem {
    /** 分组名称 */
    String getGroupName();

    /** 按钮/项的标题 */
    String getTitle();

    /** 构建 QQ 原生 QUI 列表项视图 */
    Object createView(ClassLoader cl, Activity activity, Runnable onRefresh);

    /** 是否加入原生搜索索引（默认 true） */
    default boolean isSearchable() { return true; }

    /** 搜索点击后是否跳转到动态脚本页（默认 false） */
    default boolean isPluginPage() { return false; }

    /** 绑定的 Feature 标识符，为空则不受限，有值则联动 FeatureConfig */
    default String getFeatureId() { return ""; }

    /** 特性是否已在构建期激活 */
    default boolean isFeatureEnabled() {
        String fid = getFeatureId();
        return fid == null || fid.isEmpty() || FeatureConfig.has(fid);
    }
}
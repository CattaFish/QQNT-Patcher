package com.tencent.qqnt.patch;

import android.app.Activity;

public interface SettingItem {
    /** 分组名称，如 "高级与调试", "关于", 或者你未来新增的自定义分组名 */
    String getGroupName();

    /** 按钮/项的标题，同时也作为原生搜索的匹配关键词与高亮锚点 */
    String getTitle();

    /** 构建 QQ 原生 QUI 列表项视图 */
    Object createView(ClassLoader cl, Activity activity, Runnable onRefresh);

    /** 是否加入原生搜索索引（默认 true） */
    default boolean isSearchable() { return true; }

    /** 搜索点击后是否跳转到动态脚本页（默认 false 即跳到核心设置页） */
    default boolean isPluginPage() { return false; }
}
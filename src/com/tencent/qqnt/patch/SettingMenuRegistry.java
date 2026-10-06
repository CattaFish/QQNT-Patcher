package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SettingMenuRegistry {

    private static final List<SettingItem> sItems = new ArrayList<>();

    static {
        // =========================================================================
        // 分组 1: 高级与调试
        // =========================================================================
        register(new SettingItem() {
            @Override public String getGroupName() { return "高级与调试"; }
            @Override public String getTitle() { return "调试日志输出 (Logcat)"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createSwitch(
                        cl, getTitle(), ConfigManager.isDebugLogEnabled(),
                        (btn, checked) -> {
                            ConfigManager.setDebugLogEnabled(checked);
                            ToastHelper.show(activity, "调试日志" + (checked ? " 已开启" : " 已关闭"));
                        }
                );
            }
        });

        register(new SettingItem() {
            @Override public String getGroupName() { return "高级与调试"; }
            @Override public String getTitle() { return "实时运行日志"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(
                        cl, getTitle(), "查看 (" + PLog.getBufferCount() + "条)", true, false,
                        v -> PLog.showLogDialog(activity)
                );
            }
        });

        // =========================================================================
        // 分组 2: 关于
        // =========================================================================
        register(new SettingItem() {
            @Override public String getGroupName() { return "关于"; }
            @Override public String getTitle() { return "当前版本"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createTextItem(cl, getTitle(), ConfigManager.VERSION);
            }
        });

        register(new SettingItem() {
            @Override public String getGroupName() { return "关于"; }
            @Override public String getTitle() { return "检查更新"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                boolean hasNew = ConfigManager.hasNewVersion();
                String updateText = hasNew ? "有新版本可用" : "已是最新版本";
                return NativeSettingHelper.createClickable(
                        cl, getTitle(), updateText, hasNew, hasNew,
                        v -> UpdateHelper.checkUpdate(activity, onRefresh)
                );
            }
        });

        register(new SettingItem() {
            @Override public String getGroupName() { return "关于"; }
            @Override public String getTitle() { return "Telegram 频道"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(cl, getTitle(), "加入", true, false, v -> {
                    try {
                        Intent tgIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(ConfigManager.TG_CHANNEL_URL));
                        tgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(tgIntent);
                    } catch (Throwable t) {
                        ToastHelper.show(activity, "打开链接失败: " + t.getMessage());
                    }
                });
            }
        });

        register(new SettingItem() {
            @Override public String getGroupName() { return "关于"; }
            @Override public String getTitle() { return "GitHub 仓库"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(cl, getTitle(), "前往", true, false, v -> {
                    try {
                        Intent ghIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(ConfigManager.GITHUB_REPO_URL));
                        ghIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(ghIntent);
                    } catch (Throwable t) {
                        ToastHelper.show(activity, "打开链接失败: " + t.getMessage());
                    }
                });
            }
        });

        // 💡 提示：未来你若想添加任何新按钮，直接在下方 register 即可，UI和搜索会自动同步：
        // register(new SettingItem() { ... });
    }

    public static synchronized void register(SettingItem item) {
        if (item != null && !sItems.contains(item)) {
            sItems.add(item);
        }
    }

    public static List<SettingItem> getItems() {
        return Collections.unmodifiableList(sItems);
    }

    public static SettingItem findItemByTitle(String title) {
        if (title == null) return null;
        for (SettingItem item : sItems) {
            if (title.equals(item.getTitle())) return item;
        }
        return null;
    }

    /**
     * 按注册的分组名，将所有独立项聚合为 LinkedHashMap<组名, List<View>>
     */
    public static Map<String, List<Object>> buildGroupViews(ClassLoader cl, Activity activity, Runnable onRefresh) {
        Map<String, List<Object>> groupMap = new LinkedHashMap<>();
        for (SettingItem item : sItems) {
            String group = item.getGroupName();
            Object view = item.createView(cl, activity, onRefresh);
            if (view != null) {
                groupMap.computeIfAbsent(group, k -> new ArrayList<>()).add(view);
            }
        }
        return groupMap;
    }
}
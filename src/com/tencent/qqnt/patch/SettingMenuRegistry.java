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

    // 内部支持 Feature 绑定的抽象项，无需修改 SettingItem.java 即可完美兼容
    abstract static class FeatureSettingItem implements SettingItem {
        public abstract String getFeatureId();
    }

    static {
        // =========================================================================
        // 分组 1: 数据与记录 (联动 chat_history 特性)
        // =========================================================================
        register(new FeatureSettingItem() {
            @Override public String getGroupName() { return "数据与记录"; }
            @Override public String getTitle() { return "打开好友聊天记录"; }
            @Override public String getFeatureId() { return "chat_history"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(
                        cl, getTitle(), "查询", true, false,
                        v -> ChatHistoryHelper.showFriendHistoryDialog(activity)
                );
            }
        });

        register(new FeatureSettingItem() {
            @Override public String getGroupName() { return "数据与记录"; }
            @Override public String getTitle() { return "打开群聊聊天记录"; }
            @Override public String getFeatureId() { return "chat_history"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(
                        cl, getTitle(), "查询", true, false,
                        v -> ChatHistoryHelper.showGroupHistoryDialog(activity)
                );
            }
        });

        // =========================================================================
        // 分组 2: 高级与调试
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
        // 分组 3: 关于
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
    }

    public static synchronized void register(SettingItem item) {
        if (item != null && !sItems.contains(item)) {
            sItems.add(item);
        }
    }

    private static boolean isItemActive(SettingItem item) {
        if (item instanceof FeatureSettingItem) {
            String fid = ((FeatureSettingItem) item).getFeatureId();
            return fid == null || fid.isEmpty() || FeatureConfig.has(fid);
        }
        return true;
    }

    /**
     * 仅返回在当前构建包中已激活特性的项（供 QQ 原生搜索动态建索引）
     */
    public static List<SettingItem> getItems() {
        List<SettingItem> activeItems = new ArrayList<>();
        for (SettingItem item : sItems) {
            if (isItemActive(item)) {
                activeItems.add(item);
            }
        }
        return Collections.unmodifiableList(activeItems);
    }

    public static SettingItem findItemByTitle(String title) {
        if (title == null) return null;
        for (SettingItem item : sItems) {
            if (title.equals(item.getTitle()) && isItemActive(item)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 按注册的分组名聚合视图（自动过滤掉未被激活的特性）
     */
    public static Map<String, List<Object>> buildGroupViews(ClassLoader cl, Activity activity, Runnable onRefresh) {
        Map<String, List<Object>> groupMap = new LinkedHashMap<>();
        for (SettingItem item : sItems) {
            if (!isItemActive(item)) continue;

            String group = item.getGroupName();
            Object view = item.createView(cl, activity, onRefresh);
            if (view != null) {
                groupMap.computeIfAbsent(group, k -> new ArrayList<>()).add(view);
            }
        }
        return groupMap;
    }
}
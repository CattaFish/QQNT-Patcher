package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.config.UpdateHelper;
import com.tencent.qqnt.patch.util.ChatHistoryHelper;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SettingMenuRegistry {

    private static final List<SettingItem> sItems = new ArrayList<>();

    abstract static class FeatureSettingItem implements SettingItem {
        public abstract String getFeatureId();
    }

    static {
        register(new FeatureSettingItem() {
            @Override public String getCategory() { return IPatchModule.CATEGORY_CHAT; }
            @Override public String getGroupName() { return ""; }
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
            @Override public String getCategory() { return IPatchModule.CATEGORY_CHAT; }
            @Override public String getGroupName() { return ""; }
            @Override public String getTitle() { return "打开群聊聊天记录"; }
            @Override public String getFeatureId() { return "chat_history"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(
                        cl, getTitle(), "查询", true, false,
                        v -> ChatHistoryHelper.showGroupHistoryDialog(activity)
                );
            }
        });

        register(new SettingItem() {
            @Override public String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }
            @Override public String getGroupName() { return "调试与诊断"; }
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
            @Override public String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }
            @Override public String getGroupName() { return "调试与诊断"; }
            @Override public String getTitle() { return "实时运行日志"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createClickable(
                        cl, getTitle(), "查看 (" + PLog.getBufferCount() + "条)", true, false,
                        v -> PLog.showLogDialog(activity)
                );
            }
        });

        register(new SettingItem() {
            @Override public String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }
            @Override public String getGroupName() { return "关于"; }
            @Override public String getTitle() { return "当前版本"; }
            @Override public Object createView(ClassLoader cl, Activity activity, Runnable onRefresh) {
                return NativeSettingHelper.createTextItem(cl, getTitle(), ConfigManager.VERSION);
            }
        });

        register(new SettingItem() {
            @Override public String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }
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
            @Override public String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }
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
            @Override public String getCategory() { return IPatchModule.CATEGORY_ADVANCED; }
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

    public static List<SettingItem> getItems() {
        List<SettingItem> activeItems = new ArrayList<>();
        for (SettingItem item : sItems) {
            if (isItemActive(item)) {
                activeItems.add(item);
            }
        }
        return Collections.unmodifiableList(activeItems);
    }

    public static Map<String, List<Object>> buildGroupViews(ClassLoader cl, Activity activity, Runnable onRefresh) {
        return buildGroupViews(cl, activity, null, onRefresh);
    }

    public static Map<String, List<Object>> buildGroupViews(ClassLoader cl, Activity activity, String targetCategory, Runnable onRefresh) {
        Map<String, List<Object>> groupMap = new LinkedHashMap<>();
        for (SettingItem item : sItems) {
            if (!isItemActive(item)) continue;
            if (targetCategory != null && !targetCategory.equals(item.getCategory())) continue;

            String group = item.getGroupName();
            Object view = item.createView(cl, activity, onRefresh);
            if (view != null) {
                groupMap.computeIfAbsent(group, k -> new ArrayList<>()).add(view);
            }
        }
        return groupMap;
    }
}

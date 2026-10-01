package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.View;

import com.tencent.qqnt.patch.plugin.PluginManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ZzzSettingFragment {

    public static final String EXTRA_FLAG = "open_zzz_settings";
    public static final String EXTRA_PAGE = "zzz_page_type";
    public static final String PAGE_CORE = "page_core";
    public static final String PAGE_PLUGINS = "page_plugins";

    public static void startCore(Context context) { start(context, PAGE_CORE); }
    public static void startPlugins(Context context) { start(context, PAGE_PLUGINS); }

    public static void start(Context context, String pageType) {
        try {
            ClassLoader cl = context.getClassLoader();
            Intent intent = new Intent();
            intent.putExtra(EXTRA_FLAG, true);
            intent.putExtra(EXTRA_PAGE, pageType);

            Class<?> fragmentClass = cl.loadClass("com.tencent.mobileqq.setting.generalSetting.GeneralSettingFragment");
            Class<?> activityClass = cl.loadClass("com.tencent.mobileqq.activity.QPublicFragmentActivity");

            Method startMethod = activityClass.getMethod("start", Context.class, Intent.class, Class.class);
            startMethod.invoke(null, context, intent, fragmentClass);
        } catch (Throwable t) {
            ToastHelper.show(context, "打开设置失败: " + t.getMessage());
        }
    }

    public static boolean onHijackViewCreated(Object fragment, View view, Bundle bundle) {
        try {
            ConfigManager.triggerColdStartUpdateCheck();

            Method getActivityMethod = fragment.getClass().getMethod("getActivity");
            Activity activity = (Activity) getActivityMethod.invoke(fragment);
            if (activity == null || activity.getIntent() == null) return false;
            if (!activity.getIntent().getBooleanExtra(EXTRA_FLAG, false)) return false;

            String pageType = activity.getIntent().getStringExtra(EXTRA_PAGE);
            if (pageType == null) pageType = PAGE_CORE;

            ClassLoader cl = activity.getClassLoader();

            try {
                Method setTitleMethod = fragment.getClass().getMethod("setTitle", CharSequence.class);
                if (PAGE_PLUGINS.equals(pageType)) {
                    setTitleMethod.invoke(fragment, "动态脚本");
                    PLog.i("Settings", "打开动态脚本控制台");
                } else {
                    setTitleMethod.invoke(fragment, "Zzz 设置");
                    PLog.i("Settings", "打开 Zzz 核心设置");
                }
            } catch (Throwable ignored) {}

            renderSettingsList(fragment, activity, cl, pageType);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void renderSettingsList(Object fragment, Activity activity, ClassLoader cl, String pageType) {
        try {
            Object adapter = null;
            for (Method m : fragment.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 &&
                    m.getReturnType().getName().endsWith("QUIListItemAdapter")) {
                    adapter = m.invoke(fragment);
                    break;
                }
            }
            if (adapter == null) return;

            List<Object> groups = new ArrayList<>();
            CharSequence centeredItalicFooter = createCenteredItalicFooter("Created by Zcraft with ❤️");

            if (PAGE_PLUGINS.equals(pageType)) {
                // =====================================================
                // 页面 B: 动态脚本独立管理页
                // =====================================================
                List<Object> pluginItems = new ArrayList<>();
                List<PluginManager.PluginItem> allPlugins = PluginManager.scanAllPlugins(activity);

                pluginItems.add(NativeSettingHelper.createClickable(cl, "重新扫描与重载全部脚本", "刷新", true, false, v -> {
                    ToastHelper.show(activity, "正在重载全部脚本...");
                    PluginManager.reloadAll(activity, () -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            renderSettingsList(fragment, activity, cl, pageType);
                            ToastHelper.show(activity, "重载完成并已刷新");
                        }
                    });
                }));

                if (allPlugins.isEmpty()) {
                    pluginItems.add(NativeSettingHelper.createTextItem(cl, "暂无外部脚本", "放入zzz/plugins"));
                } else {
                    for (PluginManager.PluginItem item : allPlugins) {
                        final String pId = item.id;
                        final String pName = item.name;
                        final String pSub = (item.subName != null && !item.subName.isEmpty()) ? item.subName : null;

                        pluginItems.add(NativeSettingHelper.createSwitch(
                                cl, pName + " (" + pId + ")", pSub, item.isEnabled,
                                (btn, checked) -> {
                                    ToastHelper.show(activity, pName + (checked ? " 正在启动..." : " 正在停止..."));
                                    PluginManager.setPluginActive(activity, pId, checked, () -> {
                                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                                            renderSettingsList(fragment, activity, cl, pageType);
                                            ToastHelper.show(activity, pName + (checked ? " 已启动" : " 已停止"));
                                        }
                                    });
                                }
                        ));

                        if (item.isEnabled && item.menuItems != null && !item.menuItems.isEmpty()) {
                            for (Map.Entry<String, String> entry : item.menuItems.entrySet()) {
                                final String actionName = entry.getKey();
                                final String actionCallback = entry.getValue();
                                pluginItems.add(NativeSettingHelper.createClickable(
                                        cl, "  ↳ " + actionName, "打开界面", true, false,
                                        v -> PluginManager.invokePluginMenu(pId, actionCallback, 2, "", actionName)
                                ));
                            }
                        }
                    }
                }
                groups.add(NativeSettingHelper.createGroup(cl, "已安装插件 (" + allPlugins.size() + ")", centeredItalicFooter, pluginItems));

            } else {
                // =====================================================
                // 页面 A: Zzz 核心设置页
                // =====================================================

                List<Object> funcItems = new ArrayList<>();
                for (IPatchModule module : ModuleManager.getModules()) {
                    if (!module.showInSettings()) continue;
                    final IPatchModule m = module;

                    // 1. 挂载主开关
                    funcItems.add(NativeSettingHelper.createSwitch(
                            cl, m.getName(), m.getSubName(), m.isEnabled(),
                            (btn, checked) -> {
                                m.setEnabled(checked);
                                ToastHelper.show(activity, m.getName() + (checked ? " 已开启" : " 已关闭"));

                                // ★ 若具备二级配置栏，切换开关时触发延迟重绘，即时展开/折叠二级项
                                if (m.hasConfig()) {
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                                            renderSettingsList(fragment, activity, cl, pageType);
                                        }
                                    });
                                }
                            }
                    ));

                    // ★ 核心改动：仅在开关处于开启 (isEnabled) 状态时，才展示二级配置栏
                    if (m.hasConfig() && m.isEnabled()) {
                        funcItems.add(NativeSettingHelper.createClickable(
                                cl, "  ↳ " + m.getName() + "配置", "配置", true, false,
                                v -> m.onConfigClick(activity, () -> {
                                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                                        renderSettingsList(fragment, activity, cl, pageType);
                                    }
                                })
                        ));
                    }
                }
                groups.add(NativeSettingHelper.createGroup(cl, "核心功能 (" + funcItems.size() + " 个项)", "", funcItems));

                // 2. 高级与调试
                List<Object> advancedItems = new ArrayList<>();
                advancedItems.add(NativeSettingHelper.createSwitch(
                        cl, "调试日志输出 (Logcat)", ConfigManager.isDebugLogEnabled(),
                        (btn, checked) -> {
                            ConfigManager.setDebugLogEnabled(checked);
                            ToastHelper.show(activity, "调试日志" + (checked ? " 已开启" : " 已关闭"));
                        }
                ));
                advancedItems.add(NativeSettingHelper.createClickable(
                        cl, "实时运行日志", "查看 (" + PLog.getBufferCount() + "条)", true, false,
                        v -> PLog.showLogDialog(activity)
                ));
                groups.add(NativeSettingHelper.createGroup(cl, "高级与调试", "", advancedItems));

                // 3. 关于
                List<Object> aboutItems = new ArrayList<>();
                aboutItems.add(NativeSettingHelper.createTextItem(cl, "当前版本", ConfigManager.VERSION));

                boolean hasNew = ConfigManager.hasNewVersion();
                String updateText = hasNew ? "有新版本可用" : "已是最新版本";

                aboutItems.add(NativeSettingHelper.createClickable(
                        cl, "检查更新", updateText, hasNew, hasNew,
                        v -> UpdateHelper.checkUpdate(activity, () -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                renderSettingsList(fragment, activity, cl, pageType);
                            }
                        })
                ));

                aboutItems.add(NativeSettingHelper.createClickable(cl, "Telegram 频道", "加入", true, false, v -> {
                    try {
                        Intent tgIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(ConfigManager.TG_CHANNEL_URL));
                        tgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(tgIntent);
                    } catch (Throwable t) {
                        ToastHelper.show(activity, "打开链接失败: " + t.getMessage());
                    }
                }));
                aboutItems.add(NativeSettingHelper.createClickable(cl, "GitHub 仓库", "前往", true, false, v -> {
                    try {
                        Intent ghIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(ConfigManager.GITHUB_REPO_URL));
                        ghIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(ghIntent);
                    } catch (Throwable t) {
                        ToastHelper.show(activity, "打开链接失败: " + t.getMessage());
                    }
                }));

                groups.add(NativeSettingHelper.createGroup(cl, "关于", centeredItalicFooter, aboutItems));
            }

            NativeSettingHelper.applyGroupsToAdapter(adapter, groups, cl);

        } catch (Throwable ignored) {}
    }

    private static CharSequence createCenteredItalicFooter(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            String fullText = "\n" + text;
            SpannableString sp = new SpannableString(fullText);
            int len = fullText.length();

            try {
                Class<?> alignEnumClz = Class.forName("android.text.Layout$Alignment");
                Object alignCenter = Enum.valueOf((Class<Enum>) alignEnumClz, "ALIGN_CENTER");
                Class<?> spanClz = Class.forName("android.text.style.AlignmentSpan$Standard");
                Constructor<?> ctor = spanClz.getConstructor(alignEnumClz);
                Object alignSpan = ctor.newInstance(alignCenter);
                sp.setSpan(alignSpan, 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable ignored) {}

            try {
                Class<?> styleSpanClz = Class.forName("android.text.style.StyleSpan");
                Constructor<?> styleCtor = styleSpanClz.getConstructor(int.class);
                Object italicSpan = styleCtor.newInstance(2);
                sp.setSpan(italicSpan, 1, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable ignored) {}

            return sp;
        } catch (Throwable t) {
            return text;
        }
    }
}
package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.View;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.ModuleManager;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.plugin.PluginManager;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ZzzSettingFragment {

    public static final String EXTRA_FLAG = "open_zzz_settings";
    public static final String EXTRA_PAGE = "zzz_page_type";
    public static final String EXTRA_CATEGORY = "zzz_category_type";

    public static final String PAGE_CORE = "page_core";
    public static final String PAGE_CATEGORY = "page_category";
    public static final String PAGE_PLUGINS = "page_plugins";

    public static void startCore(Context context) { 
        start(context, PAGE_CORE, null, null); 
    }

    public static void startCategory(Context context, String category) {
        start(context, PAGE_CATEGORY, category, null);
    }

    public static void startCategory(Context context, String category, String searchTitle) {
        start(context, PAGE_CATEGORY, category, searchTitle);
    }

    public static void startPlugins(Context context) { 
        start(context, PAGE_PLUGINS, null, null); 
    }
    
    public static void startPlugins(Context context, String searchTitle) { 
        start(context, PAGE_PLUGINS, null, searchTitle); 
    }

    public static void start(Context context, String pageType, String category, String searchTitle) {
        try {
            ClassLoader cl = context.getClassLoader();
            Intent intent = new Intent();
            intent.putExtra(EXTRA_FLAG, true);
            intent.putExtra(EXTRA_PAGE, pageType);
            if (category != null) {
                intent.putExtra(EXTRA_CATEGORY, category);
            }
            if (searchTitle != null && !searchTitle.trim().isEmpty()) {
                intent.putExtra("setting_search_title", searchTitle);
            }

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
            String category = activity.getIntent().getStringExtra(EXTRA_CATEGORY);

            ClassLoader cl = activity.getClassLoader();

            try {
                Method setTitleMethod = fragment.getClass().getMethod("setTitle", CharSequence.class);
                if (PAGE_PLUGINS.equals(pageType)) {
                    setTitleMethod.invoke(fragment, "动态脚本");
                } else if (PAGE_CATEGORY.equals(pageType)) {
                    if (IPatchModule.CATEGORY_CHAT.equals(category)) {
                        setTitleMethod.invoke(fragment, "聊天");
                    } else if (IPatchModule.CATEGORY_PURIFY.equals(category)) {
                        setTitleMethod.invoke(fragment, "净化");
                    } else if (IPatchModule.CATEGORY_ADVANCED.equals(category)) {
                        setTitleMethod.invoke(fragment, "高级");
                    } else {
                        setTitleMethod.invoke(fragment, "功能设置");
                    }
                } else {
                    setTitleMethod.invoke(fragment, "Zzz 设置");
                }
            } catch (Throwable ignored) {}

            renderSettingsList(fragment, activity, cl, pageType, category);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static class GroupData {
        String title;
        List<Object> items;
        GroupData(String title, List<Object> items) {
            this.title = title;
            this.items = items;
        }
    }

    public static void renderSettingsList(Object fragment, Activity activity, ClassLoader cl, String pageType, String category) {
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
            CharSequence footerNotice = createCenteredItalicFooter("Created by Zcraft with ❤️");

            if (PAGE_PLUGINS.equals(pageType)) {
                List<Object> pluginItems = new ArrayList<>();
                List<PluginManager.PluginItem> allPlugins = PluginManager.scanAllPlugins(activity);

                pluginItems.add(NativeSettingHelper.createClickable(cl, "重新扫描与重载全部脚本", "刷新", true, false, v -> {
                    ToastHelper.show(activity, "正在重载全部脚本...");
                    PluginManager.reloadAll(activity, () -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            renderSettingsList(fragment, activity, cl, pageType, category);
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
                        String subText = (item.subName != null && !item.subName.isEmpty()) 
                                ? item.subName + "  ·  ID: " + pId 
                                : "ID: " + pId;

                        pluginItems.add(NativeSettingHelper.createSwitch(
                                cl, pName, subText, item.isEnabled,
                                (btn, checked) -> {
                                    ToastHelper.show(activity, pName + (checked ? " 正在启动..." : " 正在停止..."));
                                    PluginManager.setPluginActive(activity, pId, checked, () -> {
                                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                                            renderSettingsList(fragment, activity, cl, pageType, category);
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
                groups.add(NativeSettingHelper.createGroup(cl, "已安装插件 (" + allPlugins.size() + ")", footerNotice, pluginItems));

            } else if (PAGE_CORE.equals(pageType)) {
                List<Object> categoryItems = new ArrayList<>();

                int chatEnabled = 0, chatTotal = 0;
                int purifyEnabled = 0, purifyTotal = 0;

                for (IPatchModule m : ModuleManager.getModules()) {
                    if (!m.showInSettings()) continue;
                    if (IPatchModule.CATEGORY_CHAT.equals(m.getCategory())) {
                        chatTotal++;
                        if (m.isEnabled()) chatEnabled++;
                    } else if (IPatchModule.CATEGORY_PURIFY.equals(m.getCategory())) {
                        purifyTotal++;
                        if (m.isEnabled()) purifyEnabled++;
                    }
                }

                String chatSummary = chatEnabled + "/" + chatTotal + " 已开启";
                categoryItems.add(NativeSettingHelper.createClickable(
                        cl, "聊天", chatSummary, true, false,
                        v -> startCategory(activity, IPatchModule.CATEGORY_CHAT)
                ));

                String purifySummary = purifyEnabled + "/" + purifyTotal + " 已开启";
                categoryItems.add(NativeSettingHelper.createClickable(
                        cl, "净化", purifySummary, true, false,
                        v -> startCategory(activity, IPatchModule.CATEGORY_PURIFY)
                ));

                boolean hasNew = ConfigManager.hasNewVersion();
                String advSummary = hasNew ? "发现新版本" : ConfigManager.VERSION;
                categoryItems.add(NativeSettingHelper.createClickable(
                        cl, "高级", advSummary, true, hasNew,
                        v -> startCategory(activity, IPatchModule.CATEGORY_ADVANCED)
                ));

                groups.add(NativeSettingHelper.createGroup(cl, "功能分类", footerNotice, categoryItems));

            } else {
                List<GroupData> pendingGroups = new ArrayList<>();

                List<Object> funcItems = new ArrayList<>();
                for (IPatchModule module : ModuleManager.getModules()) {
                    if (!module.showInSettings()) continue;
                    if (category != null && !category.equals(module.getCategory())) continue;

                    final IPatchModule m = module;
                    funcItems.add(NativeSettingHelper.createSwitch(
                            cl, m.getName(), m.getSubName(), m.isEnabled(),
                            (btn, checked) -> {
                                m.setEnabled(checked);
                                ToastHelper.show(activity, m.getName() + (checked ? " 已开启" : " 已关闭"));

                                if (m.hasConfig()) {
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                                            renderSettingsList(fragment, activity, cl, pageType, category);
                                        }
                                    });
                                }
                            }
                    ));

                    if (m.hasConfig() && m.isEnabled()) {
                        List<Object> subItems = m.getSubSettingItems(cl, activity, () -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                renderSettingsList(fragment, activity, cl, pageType, category);
                            }
                        });
                        if (subItems != null && !subItems.isEmpty()) {
                            funcItems.addAll(subItems);
                        }
                    }
                }

                Map<String, List<Object>> dynamicGroups = SettingMenuRegistry.buildGroupViews(cl, activity, category, () -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        renderSettingsList(fragment, activity, cl, pageType, category);
                    }
                });

                for (Map.Entry<String, List<Object>> entry : dynamicGroups.entrySet()) {
                    if (entry.getKey().isEmpty()) {
                        funcItems.addAll(entry.getValue());
                    }
                }

                if (!funcItems.isEmpty()) {
                    String headerTitle = "功能列表";
                    if (IPatchModule.CATEGORY_CHAT.equals(category)) headerTitle = "聊天功能";
                    else if (IPatchModule.CATEGORY_PURIFY.equals(category)) headerTitle = "净化与穿透";
                    pendingGroups.add(new GroupData(headerTitle, funcItems));
                }

                for (Map.Entry<String, List<Object>> entry : dynamicGroups.entrySet()) {
                    if (!entry.getKey().isEmpty()) {
                        pendingGroups.add(new GroupData(entry.getKey(), entry.getValue()));
                    }
                }

                if (pendingGroups.isEmpty()) {
                    groups.add(NativeSettingHelper.createGroup(cl, "暂无功能", footerNotice, new ArrayList<>()));
                } else {
                    for (int i = 0; i < pendingGroups.size(); i++) {
                        GroupData gd = pendingGroups.get(i);
                        CharSequence fNotice = (i == pendingGroups.size() - 1) ? footerNotice : "";
                        groups.add(NativeSettingHelper.createGroup(cl, gd.title, fNotice, gd.items));
                    }
                }
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

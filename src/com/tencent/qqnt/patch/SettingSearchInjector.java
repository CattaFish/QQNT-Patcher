package com.tencent.qqnt.patch;

import android.content.Context;
import com.tencent.mobileqq.setting.search.node.c;
import com.tencent.qqnt.patch.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;

public class SettingSearchInjector {

    public static void inject(Object rootNodeObj) {
        if (!(rootNodeObj instanceof c)) return;
        try {
            c rootNode = (c) rootNodeObj;

            // 防重复挂载检查
            ArrayList<c> currentChildren = rootNode.d();
            if (currentChildren != null) {
                for (c child : currentChildren) {
                    if (child instanceof ZzzCoreSearchNode || child instanceof ZzzPluginSearchNode) {
                        return;
                    }
                }
            }

            // 同时挂载两大标准分类节点：Zzz 设置 与 动态脚本
            rootNode.a(new ZzzCoreSearchNode());
            rootNode.a(new ZzzPluginSearchNode());
            PLog.i("Search", "已成功将 Zzz 核心功能树与动态脚本树分别挂载进原生搜索！");
        } catch (Throwable t) {
            PLog.e("Search", "挂载搜索节点异常", t);
        }
    }

    /**
     * 分类节点 1: Zzz 设置（小字显示 "Zzz 设置"，点击直达核心功能并高亮）
     */
    public static class ZzzCoreSearchNode extends c {

        @Override
        public ArrayList<c> c() {
            ArrayList<c> children = new ArrayList<>();

            // 1. 全动态提取所有已注册的核心模块 (新增模块自动感知)
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings()) {
                    children.add(new ZzzLeafSearchNode(module.getName(), false));
                }
            }

            // 2. 全动态提取独立注册的菜单项 (高级与调试、关于等)
            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && !item.isPluginPage()) {
                    children.add(new ZzzLeafSearchNode(item.getTitle(), false));
                }
            }

            return children;
        }

        @Override
        public String e() {
            return "Zzz 设置";
        }

        @Override
        public void f(String title, Context context, String search) {
            PLog.i("Search", "[核心项点击] " + title);
            ZzzSettingFragment.startCore(context, title);
        }
    }

    /**
     * 分类节点 2: 动态脚本（小字显示 "动态脚本"，点击直达插件管理页并高亮插件项）
     */
    public static class ZzzPluginSearchNode extends c {

        @Override
        public ArrayList<c> c() {
            ArrayList<c> children = new ArrayList<>();
            Context context = AppContext.get();

            // 1. 脚本操作项
            children.add(new ZzzLeafSearchNode("重新扫描与重载全部脚本", true));

            // 2. 全动态提取外部存储安装的全部插件（小字自动继承本节点标题 "动态脚本"）
            if (context != null) {
                try {
                    List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(context);
                    for (PluginManager.PluginItem p : plugins) {
                        children.add(new ZzzLeafSearchNode(p.name, true));
                    }
                } catch (Throwable ignored) {}
            }

            // 3. 注册项中标记为插件页的项
            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && item.isPluginPage()) {
                    children.add(new ZzzLeafSearchNode(item.getTitle(), true));
                }
            }

            return children;
        }

        @Override
        public String e() {
            return "动态脚本";
        }

        @Override
        public void f(String title, Context context, String search) {
            PLog.i("Search", "[脚本项点击] " + title);
            // 搜索大分类“动态脚本”自身跳入时不显示高亮；搜索具体插件名正常高亮
            String highlightTarget = "动态脚本".equals(title) ? null : title;
            ZzzSettingFragment.startPlugins(context, highlightTarget);
        }
    }

    /**
     * 叶子展示节点
     */
    public static class ZzzLeafSearchNode extends c {
        private final String title;
        private final boolean isPlugin;

        public ZzzLeafSearchNode(String title, boolean isPlugin) {
            this.title = title;
            this.isPlugin = isPlugin;
        }

        @Override
        public ArrayList<c> c() {
            return null;
        }

        @Override
        public String e() {
            return title;
        }

        @Override
        public void f(String title, Context context, String search) {
            if (isPlugin) {
                String highlightTarget = "动态脚本".equals(title) ? null : title;
                ZzzSettingFragment.startPlugins(context, highlightTarget);
            } else {
                ZzzSettingFragment.startCore(context, title);
            }
        }
    }
}
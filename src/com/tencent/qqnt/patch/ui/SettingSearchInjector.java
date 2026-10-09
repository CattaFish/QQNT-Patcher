package com.tencent.qqnt.patch.ui;

import android.content.Context;
import com.tencent.mobileqq.setting.search.node.c;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.ModuleManager;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.plugin.PluginManager;
import com.tencent.qqnt.patch.util.PLog;

import java.util.ArrayList;
import java.util.List;

public class SettingSearchInjector {

    public static void inject(Object rootNodeObj) {
        if (!(rootNodeObj instanceof c)) return;
        try {
            c rootNode = (c) rootNodeObj;

            ArrayList<c> currentChildren = rootNode.d();
            if (currentChildren != null) {
                for (c child : currentChildren) {
                    if (child instanceof ZzzCoreSearchNode || child instanceof ZzzPluginSearchNode) {
                        return;
                    }
                }
            }

            if (FeatureConfig.has("setting")) {
                rootNode.a(new ZzzCoreSearchNode());
            }
            if (FeatureConfig.has("script")) {
                rootNode.a(new ZzzPluginSearchNode());
            }
            PLog.i("Search", "已动态挂载原生搜索节点");
        } catch (Throwable t) {
            PLog.e("Search", "挂载搜索节点异常", t);
        }
    }

    public static class ZzzCoreSearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> children = new ArrayList<>();
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings()) {
                    children.add(new ZzzLeafSearchNode(module.getName(), false, module.getCategory()));
                }
            }
            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && !item.isPluginPage()) {
                    children.add(new ZzzLeafSearchNode(item.getTitle(), false, item.getCategory()));
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
            ZzzSettingFragment.startCore(context);
        }
    }

    public static class ZzzPluginSearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> children = new ArrayList<>();
            Context context = AppContext.get();

            children.add(new ZzzLeafSearchNode("重新扫描与重载全部脚本", true, null));

            if (context != null) {
                try {
                    List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(context);
                    for (PluginManager.PluginItem p : plugins) {
                        children.add(new ZzzLeafSearchNode(p.name, true, null));
                    }
                } catch (Throwable ignored) {}
            }

            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && item.isPluginPage()) {
                    children.add(new ZzzLeafSearchNode(item.getTitle(), true, null));
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
            String highlightTarget = "动态脚本".equals(title) ? null : title;
            ZzzSettingFragment.startPlugins(context, highlightTarget);
        }
    }

    public static class ZzzLeafSearchNode extends c {
        private final String title;
        private final boolean isPlugin;
        private final String category;

        public ZzzLeafSearchNode(String title, boolean isPlugin) {
            this(title, isPlugin, null);
        }

        public ZzzLeafSearchNode(String title, boolean isPlugin, String category) {
            this.title = title;
            this.isPlugin = isPlugin;
            this.category = category;
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
            } else if (category != null && !category.isEmpty()) {
                ZzzSettingFragment.startCategory(context, category, title);
            } else {
                ZzzSettingFragment.startCore(context);
            }
        }
    }
}

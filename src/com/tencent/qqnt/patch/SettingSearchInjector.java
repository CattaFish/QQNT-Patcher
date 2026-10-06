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
            
            // 防重复挂载检测
            ArrayList<c> currentChildren = rootNode.d();
            if (currentChildren != null) {
                for (c child : currentChildren) {
                    if (child instanceof ZzzParentSearchNode) {
                        return;
                    }
                }
            }

            // 挂载全动态 Zzz 分类根节点
            rootNode.a(new ZzzParentSearchNode());
            PLog.i("Search", "已成功将全动态 Zzz 功能树挂载进前台搜索索引！");
        } catch (Throwable t) {
            PLog.e("Search", "挂载搜索节点异常", t);
        }
    }

    /**
     * Zzz 一级分类父节点
     */
    public static class ZzzParentSearchNode extends c {

        @Override
        public ArrayList<c> c() {
            ArrayList<c> children = new ArrayList<>();
            Context context = AppContext.get();

            // 1. 全动态提取所有已注册的核心模块 (新增模块自动感知)
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings()) {
                    children.add(new ZzzLeafSearchNode(module.getName(), false));
                }
            }

            // 2. 动态脚本总入口
            children.add(new ZzzLeafSearchNode("动态脚本", true));

            // 3. 全动态提取外部存储安装的全部插件
            if (context != null) {
                try {
                    List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(context);
                    for (PluginManager.PluginItem p : plugins) {
                        children.add(new ZzzLeafSearchNode(p.name, true));
                    }
                } catch (Throwable ignored) {}
            }

            // 4. 其他常规设置快捷项
            children.add(new ZzzLeafSearchNode("实时运行日志", false));
            children.add(new ZzzLeafSearchNode("检查更新", false));

            return children;
        }

        @Override
        public String e() {
            return "Zzz 设置";
        }

        @Override
        public void f(String title, Context context, String search) {
            PLog.i("Search", "用户在搜索结果中点击了: " + title);
            if ("动态脚本".equals(title) || isPluginItem(title)) {
                ZzzSettingFragment.startPlugins(context);
            } else {
                ZzzSettingFragment.startCore(context);
            }
        }

        private boolean isPluginItem(String title) {
            Context ctx = AppContext.get();
            if (ctx == null) return false;
            try {
                List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(ctx);
                for (PluginManager.PluginItem p : plugins) {
                    if (title.equals(p.name) || title.equals(p.id)) return true;
                }
            } catch (Throwable ignored) {}
            return false;
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
                ZzzSettingFragment.startPlugins(context);
            } else {
                ZzzSettingFragment.startCore(context);
            }
        }
    }
}
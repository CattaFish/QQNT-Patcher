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
                    if (child instanceof ZzzParentSearchNode) {
                        return;
                    }
                }
            }

            // 注入动态 Zzz 根节点
            rootNode.a(new ZzzParentSearchNode());
            PLog.i("Search", "已成功将全动态 Zzz 功能树挂载进原生搜索索引！");
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

            // 1. 全动态提取所有已注册的核心模块 (如喵喵助手、防撤回、闪照、平板模式等)
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings()) {
                    children.add(new ZzzLeafSearchNode(module.getName(), false));
                }
            }

            // 2. 动态脚本总入口与操作项
            children.add(new ZzzLeafSearchNode("动态脚本", true));
            children.add(new ZzzLeafSearchNode("重新扫描与重载全部脚本", true));

            // 3. 全动态提取外部存储安装的全部插件
            if (context != null) {
                try {
                    List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(context);
                    for (PluginManager.PluginItem p : plugins) {
                        children.add(new ZzzLeafSearchNode(p.name, true));
                    }
                } catch (Throwable ignored) {}
            }

            // 4. 高级与调试
            children.add(new ZzzLeafSearchNode("调试日志输出 (Logcat)", false));
            children.add(new ZzzLeafSearchNode("实时运行日志", false));

            // 5. 关于（补全：当前版本、检查更新、TG、GitHub 全部可搜）
            children.add(new ZzzLeafSearchNode("当前版本", false));
            children.add(new ZzzLeafSearchNode("检查更新", false));
            children.add(new ZzzLeafSearchNode("Telegram 频道", false));
            children.add(new ZzzLeafSearchNode("GitHub 仓库", false));

            return children;
        }

        @Override
        public String e() {
            return "Zzz 设置";
        }

        @Override
        public void f(String title, Context context, String search) {
            PLog.i("Search", "用户在搜索结果中点击了: " + title);

            // 智能分流并带上点击项标题触发原生滚动与闪烁高亮
            if ("动态脚本".equals(title) || "重新扫描与重载全部脚本".equals(title) || isPluginItem(title)) {
                ZzzSettingFragment.startPlugins(context, title);
            } else {
                ZzzSettingFragment.startCore(context, title);
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
                ZzzSettingFragment.startPlugins(context, title);
            } else {
                ZzzSettingFragment.startCore(context, title);
            }
        }
    }
}
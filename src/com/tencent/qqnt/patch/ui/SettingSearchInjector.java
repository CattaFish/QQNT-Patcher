package com.tencent.qqnt.patch.ui;

import android.content.Context;
import com.tencent.mobileqq.setting.search.node.c;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.ModuleManager;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.plugin.PluginManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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
            PLog.i("Search", "已动态挂载原生三层搜索节点");
        } catch (Throwable t) {
            PLog.e("Search", "挂载搜索节点异常", t);
        }
    }

    /**
     * 搜索结果在渲染前置顶重排（将属于 Zzz 设置 / 动态脚本 的结果排在最顶部）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void prioritizeSearchResults(Object listObj) {
        if (!(listObj instanceof List)) return;
        try {
            List list = (List) listObj;
            if (list.size() <= 1) return;

            Collections.sort(list, (o1, o2) -> {
                boolean isZzz1 = isZzzSearchResult(o1);
                boolean isZzz2 = isZzzSearchResult(o2);
                if (isZzz1 && !isZzz2) return -1; // o1 排在前面
                if (!isZzz1 && isZzz2) return 1;  // o2 排在前面
                return 0; // 稳定排序，保持原内部相对顺序
            });
        } catch (Throwable ignored) {}
    }

    private static boolean isZzzSearchResult(Object resultObj) {
        if (resultObj == null) return false;
        try {
            // 检查 description 路径（k.a()）
            Method descM = resultObj.getClass().getMethod("a");
            Object desc = descM.invoke(resultObj);
            if (desc instanceof String) {
                String s = (String) desc;
                if (s.contains("Zzz 设置") || s.contains("动态脚本")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            // 检查 resultNode（k.c()）
            Method nodeM = resultObj.getClass().getMethod("c");
            Object node = nodeM.invoke(resultObj);
            if (node != null) {
                String className = node.getClass().getName();
                if (className.contains("Zzz") || className.contains("patch")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    /**
     * 官方标准叶子节点实现（对齐 a.smali 规范，c() 恒为 null，杜绝构造期 NPE）
     */
    public static class ZzzLeafNode extends c {
        private final String title;

        public ZzzLeafNode(String title) {
            this.title = title;
        }

        @Override
        public ArrayList<c> c() {
            return null; // 叶子节点无子节点
        }

        @Override
        public String e() {
            return title != null ? title : "";
        }

        @Override
        public void f(String title, Context context, String search) {
            // 空实现，由父节点统一分发
        }
    }

    /**
     * 一级容器节点：Zzz 设置（对齐 b.smali 规范，无参构造函数）
     */
    public static class ZzzCoreSearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> categories = new ArrayList<>();
            categories.add(new ZzzChatSearchNode());
            categories.add(new ZzzPurifySearchNode());
            categories.add(new ZzzAdvancedSearchNode());
            return categories;
        }

        @Override
        public String e() {
            return "Zzz 设置";
        }

        @Override
        public void f(String title, Context context, String search) {
            if ("聊天".equals(title)) {
                ZzzSettingFragment.startCategory(context, IPatchModule.CATEGORY_CHAT);
            } else if ("净化".equals(title)) {
                ZzzSettingFragment.startCategory(context, IPatchModule.CATEGORY_PURIFY);
            } else if ("高级".equals(title)) {
                ZzzSettingFragment.startCategory(context, IPatchModule.CATEGORY_ADVANCED);
            } else {
                ZzzSettingFragment.startCore(context);
            }
        }
    }

    /**
     * 二级分类节点：聊天（无参构造，硬编码分类）
     */
    public static class ZzzChatSearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> leaves = new ArrayList<>();
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings() && IPatchModule.CATEGORY_CHAT.equals(module.getCategory())) {
                    leaves.add(new ZzzLeafNode(module.getName()));
                }
            }
            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && !item.isPluginPage() && IPatchModule.CATEGORY_CHAT.equals(item.getCategory())) {
                    leaves.add(new ZzzLeafNode(item.getTitle()));
                }
            }
            return leaves;
        }

        @Override
        public String e() {
            return "聊天";
        }

        @Override
        public void f(String title, Context context, String search) {
            ZzzSettingFragment.startCategory(context, IPatchModule.CATEGORY_CHAT, title);
        }
    }

    /**
     * 二级分类节点：净化（无参构造，硬编码分类）
     */
    public static class ZzzPurifySearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> leaves = new ArrayList<>();
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings() && IPatchModule.CATEGORY_PURIFY.equals(module.getCategory())) {
                    leaves.add(new ZzzLeafNode(module.getName()));
                }
            }
            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && !item.isPluginPage() && IPatchModule.CATEGORY_PURIFY.equals(item.getCategory())) {
                    leaves.add(new ZzzLeafNode(item.getTitle()));
                }
            }
            return leaves;
        }

        @Override
        public String e() {
            return "净化";
        }

        @Override
        public void f(String title, Context context, String search) {
            ZzzSettingFragment.startCategory(context, IPatchModule.CATEGORY_PURIFY, title);
        }
    }

    /**
     * 二级分类节点：高级（无参构造，硬编码分类）
     */
    public static class ZzzAdvancedSearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> leaves = new ArrayList<>();
            for (IPatchModule module : ModuleManager.getModules()) {
                if (module.showInSettings() && IPatchModule.CATEGORY_ADVANCED.equals(module.getCategory())) {
                    leaves.add(new ZzzLeafNode(module.getName()));
                }
            }
            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && !item.isPluginPage() && IPatchModule.CATEGORY_ADVANCED.equals(item.getCategory())) {
                    leaves.add(new ZzzLeafNode(item.getTitle()));
                }
            }
            return leaves;
        }

        @Override
        public String e() {
            return "高级";
        }

        @Override
        public void f(String title, Context context, String search) {
            ZzzSettingFragment.startCategory(context, IPatchModule.CATEGORY_ADVANCED, title);
        }
    }

    /**
     * 独立一级分类节点：动态脚本（无参构造）
     */
    public static class ZzzPluginSearchNode extends c {
        @Override
        public ArrayList<c> c() {
            ArrayList<c> children = new ArrayList<>();
            Context context = AppContext.get();

            children.add(new ZzzLeafNode("重新扫描与重载全部脚本"));

            if (context != null) {
                try {
                    List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(context);
                    for (PluginManager.PluginItem p : plugins) {
                        children.add(new ZzzLeafNode(p.name));
                    }
                } catch (Throwable ignored) {}
            }

            for (SettingItem item : SettingMenuRegistry.getItems()) {
                if (item.isSearchable() && item.isPluginPage()) {
                    children.add(new ZzzLeafNode(item.getTitle()));
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
}
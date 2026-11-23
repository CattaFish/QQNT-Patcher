package com.tencent.qqnt.patch.modules.purify;

import android.app.Activity;
import android.widget.TextView;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SimplifySettingMeModule implements IPatchModule {

    private static final String TAG = "SimplifySettingMe";
    public static final String FEATURE_ID = "simplify_setting_me";
    private static final String KEY_HIDDEN_ITEMS = "zzz_simplify_setting_me_items";
    private static final String PROTECTED_SETTING_ID = "d_setting";

    private static final class MenuItemDef {
        final String pluginId;
        final String title;

        MenuItemDef(String pluginId, String title) {
            this.pluginId = pluginId;
            this.title = title;
        }
    }

    // 侧滑栏默认显示的入口（与界面从上到下顺序一致）
    private static final MenuItemDef[] DEFAULT_ITEMS = new MenuItemDef[]{
            new MenuItemDef("d_album", "相册"),
            new MenuItemDef("d_favorite", "收藏"),
            new MenuItemDef("d_document", "文件"),
            new MenuItemDef("d_qqwallet", "钱包"),
            new MenuItemDef("d_vip_identity", "会员中心"),
            new MenuItemDef("d_decoration", "个性装扮"),
            new MenuItemDef("d_vip_card", "免流量"),
    };

    // 默认未显示或由云端下发的扩展入口
    private static final MenuItemDef[] EXTRA_ITEMS = new MenuItemDef[]{
            new MenuItemDef("d_financial", "小金库"),
            new MenuItemDef("d_zplan", "超级QQ秀"),
            new MenuItemDef("d_my_game", "我的游戏"),
            new MenuItemDef("d_smallworld", "我的小世界"),
            new MenuItemDef("d_intimate_space", "亲密空间"),
            new MenuItemDef("d_video", "我的视频"),
    };

    private static volatile Set<String> sHiddenIds = null;
    private static final AtomicInteger sConfigVersion = new AtomicInteger(0);
    private static final Map<Object, Integer> sPanelVersions = new WeakHashMap<>();

    private static volatile Class<?> sCachedWrapperClass = null;
    private static volatile Field sCachedBeanField = null;

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "侧滑栏精简";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public String getSubName() {
        if (!isEnabled()) {
            return "隐藏侧滑栏中的指定功能入口";
        }
        int count = getHiddenIds().size();
        if (count == 0) {
            return "在下方配置需要隐藏的入口";
        }
        return "已隐藏 " + count + " 项入口";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public int getOrder() {
        return 15;
    }

    @Override
    public boolean hasConfig() {
        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        IPatchModule.super.setEnabled(enabled);
        sConfigVersion.incrementAndGet();
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        int count = getHiddenIds().size();
        String rightText = count > 0 ? ("已选 " + count + " 项") : "未配置";
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 隐藏项配置", rightText, true, false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final Set<String> pending = new LinkedHashSet<>(getHiddenIds());
        SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "侧边栏精简");

        builder.addCustomView(createSectionLabel(activity, builder, "默认入口", 2));
        for (MenuItemDef item : DEFAULT_ITEMS) {
            final String id = item.pluginId;
            builder.addSwitch(item.title, null, pending.contains(id), checked -> {
                if (checked) {
                    pending.add(id);
                } else {
                    pending.remove(id);
                }
            });
        }

        builder.addCustomView(createSectionLabel(activity, builder, "其他入口", 12));
        for (MenuItemDef item : EXTRA_ITEMS) {
            final String id = item.pluginId;
            builder.addSwitch(item.title, null, pending.contains(id), checked -> {
                if (checked) {
                    pending.add(id);
                } else {
                    pending.remove(id);
                }
            });
        }

        builder.setNeutralButton("全部显示", dialog -> {
            saveHiddenIds(Collections.emptySet());
            dialog.dismiss();
            ToastHelper.show(activity, "已清空隐藏项");
            if (onSaved != null) {
                onSaved.run();
            }
        });

        builder.setNegativeButton("取消", dialog -> dialog.dismiss());

        builder.setPositiveButton("保存", dialog -> {
            saveHiddenIds(pending);
            dialog.dismiss();
            ToastHelper.show(activity, "已保存 (" + pending.size() + " 项)");
            if (onSaved != null) {
                onSaved.run();
            }
        });

        builder.show();
    }

    private static TextView createSectionLabel(Activity activity, SettingDialogBuilder builder, String text, int topDp) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(builder.subTextColor);
        tv.setPadding(
                SettingDialogBuilder.dp2px(activity, 4),
                SettingDialogBuilder.dp2px(activity, topDp),
                0,
                SettingDialogBuilder.dp2px(activity, 4)
        );
        return tv;
    }

    private static Set<String> getHiddenIds() {
        Set<String> cached = sHiddenIds;
        if (cached != null) {
            return cached;
        }
        Set<String> parsed = new HashSet<>();
        try {
            String raw = ConfigManager.getString(KEY_HIDDEN_ITEMS, "");
            if (raw != null && !raw.isEmpty()) {
                String[] parts = raw.split(",");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !PROTECTED_SETTING_ID.equals(trimmed)) {
                        parsed.add(trimmed);
                    }
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "读取配置失败: " + t.getMessage());
        }
        Set<String> unmodifiable = Collections.unmodifiableSet(parsed);
        sHiddenIds = unmodifiable;
        return unmodifiable;
    }

    private static void saveHiddenIds(Set<String> ids) {
        Set<String> clean = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (String id : ids) {
                if (id == null) continue;
                String trimmed = id.trim();
                if (trimmed.isEmpty() || PROTECTED_SETTING_ID.equals(trimmed)) continue;
                if (clean.add(trimmed)) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(trimmed);
                }
            }
        }
        ConfigManager.setString(KEY_HIDDEN_ITEMS, sb.toString());
        sHiddenIds = Collections.unmodifiableSet(new HashSet<>(clean));
        sConfigVersion.incrementAndGet();
        PLog.i(TAG, "更新隐藏项: " + sb);
    }

    private static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled(FEATURE_ID, false);
    }

    /**
     * 由 QQSettingMeUtils.needHideForMenu(QQSettingMeBizBean)Z 入口调用
     * 返回 true 表示强制隐藏该侧滑栏入口
     */
    public static boolean shouldHideItem(String pluginId) {
        if (pluginId == null || pluginId.isEmpty() || PROTECTED_SETTING_ID.equals(pluginId)) {
            return false;
        }
        try {
            if (!isModuleActive()) {
                return false;
            }
            return getHiddenIds().contains(pluginId);
        } catch (Throwable t) {
            PLog.w(TAG, "shouldHideItem 异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 由 QQSettingMeMenuPanelPartV3 的 setItems 调用前触发
     * 清理因隐藏分组产生的首尾多余分割线及连续重复分割线
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void sanitizeDividers(List items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            if (!isModuleActive()) {
                return;
            }

            List cleaned = new ArrayList(items.size());
            boolean hasRealItem = false;
            Object pendingDivider = null;

            for (int i = 0; i < items.size(); i++) {
                Object entry = items.get(i);
                if (entry == null) {
                    continue;
                }
                if (isDividerWrapper(entry)) {
                    if (hasRealItem) {
                        pendingDivider = entry;
                    }
                } else {
                    if (pendingDivider != null) {
                        cleaned.add(pendingDivider);
                        pendingDivider = null;
                    }
                    cleaned.add(entry);
                    hasRealItem = true;
                }
            }

            if (cleaned.size() != items.size()) {
                items.clear();
                items.addAll(cleaned);
            }
        } catch (Throwable t) {
            PLog.w(TAG, "sanitizeDividers 异常: " + t.getMessage());
        }
    }

    /**
     * 由 QQSettingMeMenuPanelPartV3.onPartResume 调用
     * 仅在配置或总开关发生变化后返回 true，驱动面板重刷
     */
    public static boolean consumePanelDirty(Object panelPart) {
        if (panelPart == null) {
            return false;
        }
        try {
            int currentVersion = sConfigVersion.get();
            synchronized (sPanelVersions) {
                Integer lastVersion = sPanelVersions.get(panelPart);
                if (lastVersion == null) {
                    sPanelVersions.put(panelPart, currentVersion);
                    return false;
                }
                if (lastVersion.intValue() != currentVersion) {
                    sPanelVersions.put(panelPart, currentVersion);
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "consumePanelDirty 异常: " + t.getMessage());
        }
        return false;
    }

    /**
     * 由小金库异步回插 Runnable 调用，避免隐藏前置项后原始下标越界崩溃
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void safeInsertItem(ArrayList list, int index, Object item) {
        if (list == null || item == null) {
            return;
        }
        try {
            int safeIndex = index;
            if (safeIndex < 0) {
                safeIndex = 0;
            } else if (safeIndex > list.size()) {
                safeIndex = list.size();
            }
            list.add(safeIndex, item);
        } catch (Throwable t) {
            PLog.w(TAG, "safeInsertItem 异常: " + t.getMessage());
        }
    }

    private static boolean isDividerWrapper(Object wrapper) {
        try {
            Class<?> cls = wrapper.getClass();
            Field beanField = sCachedBeanField;
            if (beanField == null || sCachedWrapperClass != cls) {
                beanField = resolveBizBeanField(cls);
                sCachedWrapperClass = cls;
                sCachedBeanField = beanField;
            }
            if (beanField == null) {
                return false;
            }
            return beanField.get(wrapper) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field resolveBizBeanField(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType().getName().endsWith("QQSettingMeBizBean")) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }
}
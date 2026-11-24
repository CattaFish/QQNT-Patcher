package com.tencent.qqnt.patch.modules.purify;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private static final String FLAG_HIDE_RED_DOT = "zzz_simplify_setting_me_hide_red_dot";
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
    private static volatile Boolean sHideRedDot = null;
    private static final AtomicInteger sConfigVersion = new AtomicInteger(0);
    private static final Map<Object, Integer> sPanelVersions = new WeakHashMap<>();

    private static volatile Class<?> sCachedWrapperClass = null;
    private static volatile Field sCachedBeanField = null;

    private static volatile Class<?> sCachedSectionClass = null;
    private static volatile Field sCachedRedContainerField = null;
    private static volatile Field sCachedSubTitleField = null;

    private static volatile Class<?> sCachedPanelClass = null;
    private static volatile Field sCachedRecyclerViewField = null;
    private static volatile Method sCachedGetAdapterMethod = null;
    private static volatile Method sCachedNotifyChangedMethod = null;

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
        boolean hideRed = isHideRedDotConfigOn();
        if (count == 0 && !hideRed) {
            return "在下方配置需要隐藏的入口";
        }
        if (count == 0) {
            return "已隐藏入口红点推广";
        }
        if (hideRed) {
            return "已隐藏 " + count + " 项入口与红点推广";
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
        boolean hideRed = isHideRedDotConfigOn();
        String rightText;
        if (count > 0 && hideRed) {
            rightText = count + " 项 / 去红点";
        } else if (count > 0) {
            rightText = "已选 " + count + " 项";
        } else if (hideRed) {
            rightText = "仅去红点";
        } else {
            rightText = "未配置";
        }
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
        final boolean[] pendingHideRed = new boolean[]{ isHideRedDotConfigOn() };

        SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "侧滑栏精简");

        builder.addSwitch("隐藏红点推广", null, pendingHideRed[0], checked -> pendingHideRed[0] = checked);

        builder.addCustomView(createSectionLabel(activity, builder, "默认入口", 12));
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

        builder.setNeutralButton("重置", dialog -> {
            saveConfig(Collections.emptySet(), false);
            dialog.dismiss();
            ToastHelper.show(activity, "已重置");
            if (onSaved != null) {
                onSaved.run();
            }
        });

        builder.setNegativeButton("取消", dialog -> dialog.dismiss());

        builder.setPositiveButton("保存", dialog -> {
            saveConfig(pending, pendingHideRed[0]);
            dialog.dismiss();
            ToastHelper.show(activity, "已保存");
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

    private static boolean isHideRedDotConfigOn() {
        Boolean cached = sHideRedDot;
        if (cached != null) {
            return cached;
        }
        boolean val = false;
        try {
            val = ConfigManager.hasFlag(FLAG_HIDE_RED_DOT);
        } catch (Throwable t) {
            PLog.w(TAG, "读取红点配置失败: " + t.getMessage());
        }
        sHideRedDot = val;
        return val;
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

    private static void saveConfig(Set<String> ids, boolean hideRedDot) {
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
        ConfigManager.setFlag(FLAG_HIDE_RED_DOT, hideRedDot);
        sHiddenIds = Collections.unmodifiableSet(new HashSet<>(clean));
        sHideRedDot = hideRedDot;
        sConfigVersion.incrementAndGet();
        PLog.i(TAG, "更新配置: hideRedDot=" + hideRedDot + ", items=" + sb);
    }

    private static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled(FEATURE_ID, false);
    }

    /**
     * 由 QQSettingMeUtils.needHideForMenu(QQSettingMeBizBean)Z 入口调用
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
     * 由 QQSettingMeMenuPanelPartV3 红点推送方法入口调用
     */
    public static boolean shouldHideRedDot() {
        try {
            return isModuleActive() && isHideRedDotConfigOn();
        } catch (Throwable t) {
            PLog.w(TAG, "shouldHideRedDot 异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 由 section/c.onBindData 调用：拦截天书红点初始化，同时清理红点容器与推广副标题
     */
    public static boolean filterSectionRedDot(Object sectionObj, boolean originalTianshuEnabled) {
        if (!shouldHideRedDot()) {
            return originalTianshuEnabled;
        }
        try {
            if (sectionObj != null) {
                ensureSectionFields(sectionObj.getClass());
                if (sCachedRedContainerField != null) {
                    Object container = sCachedRedContainerField.get(sectionObj);
                    if (container instanceof ViewGroup) {
                        ((ViewGroup) container).removeAllViews();
                    }
                    if (container instanceof View) {
                        ((View) container).setVisibility(View.GONE);
                    }
                }
                if (sCachedSubTitleField != null) {
                    Object subTitle = sCachedSubTitleField.get(sectionObj);
                    if (subTitle instanceof View) {
                        ((View) subTitle).setVisibility(View.GONE);
                    }
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "filterSectionRedDot 异常: " + t.getMessage());
        }
        return false;
    }

    /**
     * 由 QQSettingMeMenuPanelPartV3 的 setItems 调用前触发，清理多余分割线
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
     * 配置变更后强制重绑现有 ViewHolder，使“隐藏红点推广”也能免重启即时生效
     */
    public static void notifyPanelAdapterChanged(Object panelPart) {
        if (panelPart == null) {
            return;
        }
        try {
            ensurePanelFields(panelPart.getClass());
            if (sCachedRecyclerViewField == null) {
                return;
            }
            Object rv = sCachedRecyclerViewField.get(panelPart);
            if (rv == null) {
                return;
            }
            if (sCachedGetAdapterMethod == null) {
                sCachedGetAdapterMethod = rv.getClass().getMethod("getAdapter");
            }
            Object adapter = sCachedGetAdapterMethod.invoke(rv);
            if (adapter == null) {
                return;
            }
            if (sCachedNotifyChangedMethod == null) {
                sCachedNotifyChangedMethod = adapter.getClass().getMethod("notifyDataSetChanged");
            }
            sCachedNotifyChangedMethod.invoke(adapter);
        } catch (Throwable t) {
            PLog.w(TAG, "notifyPanelAdapterChanged 异常: " + t.getMessage());
        }
    }

    /**
     * 小金库异步回插列表下标保护
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

    private static void ensureSectionFields(Class<?> cls) {
        if (sCachedSectionClass == cls) {
            return;
        }
        Field redContainer = null;
        Field subTitle = null;
        for (Field f : cls.getDeclaredFields()) {
            Class<?> type = f.getType();
            if (type == View.class) {
                f.setAccessible(true);
                redContainer = f;
            } else if (type.getName().endsWith("QUIBlendTextView")) {
                f.setAccessible(true);
                subTitle = f;
            }
        }
        sCachedRedContainerField = redContainer;
        sCachedSubTitleField = subTitle;
        sCachedSectionClass = cls;
    }

    private static void ensurePanelFields(Class<?> cls) {
        if (sCachedPanelClass == cls) {
            return;
        }
        Field rvField = null;
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType().getName().endsWith("RecyclerView")) {
                f.setAccessible(true);
                rvField = f;
                break;
            }
        }
        sCachedRecyclerViewField = rvField;
        sCachedPanelClass = cls;
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
package com.tencent.qqnt.patch.util;

import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import me.yxp.qfun.utils.ui.ThemeHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CustomIconHelper {

    private static final String TAG = "CustomIcon";
    private static final String DEFAULT_ALIAS = "com.tencent.mobileqq.activity.ZzzDefaultSplashActivity";
    private static final String SPLASH_ACTIVITY = "com.tencent.mobileqq.activity.SplashActivity";

    public static class IconOption {
        public final String id;
        public final String name;
        public final String assetPath;
        public final boolean isDefault;

        public IconOption(String id, String name, String assetPath, boolean isDefault) {
            this.id = id;
            this.name = name;
            this.assetPath = assetPath;
            this.isDefault = isDefault;
        }
    }

    public static class NameOption {
        public final String id;
        public final String name;
        public final boolean isDefault;

        public NameOption(String id, String name, boolean isDefault) {
            this.id = id;
            this.name = name;
            this.isDefault = isDefault;
        }
    }

    public static class IconConfig {
        public String defaultAlias = DEFAULT_ALIAS;
        public final List<IconOption> icons = new ArrayList<>();
        public final List<NameOption> names = new ArrayList<>();
        public final Map<String, String> aliases = new LinkedHashMap<>(); // "iconId|nameId" -> aliasClassName
    }

    public static IconConfig loadConfig(Context context) {
        IconConfig cfg = new IconConfig();
        if (context == null) {
            cfg.icons.add(new IconOption("default", "默认图标", "", true));
            cfg.names.add(new NameOption("default", "QQ (默认)", true));
            cfg.aliases.put("default|default", cfg.defaultAlias);
            return cfg;
        }

        try (InputStream is = PatchAssetHelper.openStream(context, "zzz_icons.json")) {
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);

                JSONObject root = new JSONObject(sb.toString());
                String def = root.optString("default_alias", DEFAULT_ALIAS);
                if (!def.isEmpty()) cfg.defaultAlias = def;

                JSONArray iconArr = root.optJSONArray("icons");
                if (iconArr != null) {
                    for (int i = 0; i < iconArr.length(); i++) {
                        JSONObject item = iconArr.optJSONObject(i);
                        if (item == null) continue;
                        String id = item.optString("id", "");
                        String name = item.optString("name", "图标 " + (i + 1));
                        String asset = item.optString("asset", "");
                        boolean isDef = item.optBoolean("is_default", "default".equals(id));
                        if (!id.isEmpty()) {
                            cfg.icons.add(new IconOption(id, name, asset, isDef));
                        }
                    }
                }

                JSONArray nameArr = root.optJSONArray("names");
                if (nameArr != null) {
                    for (int i = 0; i < nameArr.length(); i++) {
                        JSONObject item = nameArr.optJSONObject(i);
                        if (item == null) continue;
                        String id = item.optString("id", "");
                        String name = item.optString("name", "");
                        boolean isDef = item.optBoolean("is_default", "default".equals(id));
                        if (!id.isEmpty() && !name.isEmpty()) {
                            cfg.names.add(new NameOption(id, name, isDef));
                        }
                    }
                }

                JSONObject aliasMapObj = root.optJSONObject("aliases");
                if (aliasMapObj != null) {
                    Iterator<String> keys = aliasMapObj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String v = aliasMapObj.optString(k, "");
                        if (!v.isEmpty()) {
                            cfg.aliases.put(k, v);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "读取 zzz_icons.json 异常: " + t.getMessage());
        }

        if (cfg.icons.isEmpty()) {
            cfg.icons.add(new IconOption("default", "默认图标", "", true));
        }
        if (cfg.names.isEmpty()) {
            cfg.names.add(new NameOption("default", "QQ (默认)", true));
        }
        if (cfg.aliases.isEmpty()) {
            cfg.aliases.put("default|default", cfg.defaultAlias);
        }
        return cfg;
    }

    public static String getCurrentIconName(Context context) {
        if (context == null) return "默认";
        IconConfig cfg = loadConfig(context);
        String[] activePair = resolveActivePair(context, cfg);
        IconOption iconOpt = findIconById(cfg, activePair[0]);
        NameOption nameOpt = findNameById(cfg, activePair[1]);

        String iconTitle = iconOpt != null ? iconOpt.name : "默认图标";
        if (nameOpt != null && !nameOpt.isDefault) {
            return iconTitle + " · " + nameOpt.name;
        }
        return iconTitle;
    }

    private static String[] resolveActivePair(Context context, IconConfig cfg) {
        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();

        for (Map.Entry<String, String> entry : cfg.aliases.entrySet()) {
            String key = entry.getKey();
            String alias = entry.getValue();
            if ("default|default".equals(key)) continue;
            try {
                int state = pm.getComponentEnabledSetting(new ComponentName(pkg, alias));
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    String[] parts = key.split("\\|", 2);
                    if (parts.length == 2) return parts;
                }
            } catch (Throwable ignored) {}
        }
        return new String[]{"default", "default"};
    }

    private static IconOption findIconById(IconConfig cfg, String id) {
        for (IconOption o : cfg.icons) {
            if (o.id.equals(id)) return o;
        }
        return cfg.icons.isEmpty() ? null : cfg.icons.get(0);
    }

    private static NameOption findNameById(IconConfig cfg, String id) {
        for (NameOption o : cfg.names) {
            if (o.id.equals(id)) return o;
        }
        return cfg.names.isEmpty() ? null : cfg.names.get(0);
    }

    public static boolean switchCombination(Context context, IconConfig cfg, String iconId, String nameId) {
        if (context == null || cfg == null) return false;
        String targetAlias = cfg.aliases.get(iconId + "|" + nameId);
        if (targetAlias == null || targetAlias.isEmpty()) {
            targetAlias = cfg.defaultAlias;
        }
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();

            // 1. 先启用目标别名（保证任何时刻都至少有一个 LAUNCHER 组件可用）
            ComponentName targetComp = new ComponentName(pkg, targetAlias);
            pm.setComponentEnabledSetting(
                    targetComp,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );

            // 2. 再禁用其余别名
            for (String alias : cfg.aliases.values()) {
                if (alias.equals(targetAlias)) continue;
                try {
                    pm.setComponentEnabledSetting(
                            new ComponentName(pkg, alias),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                    );
                } catch (Throwable ignored) {}
            }

            // 3. 兜底清理可能被启用过的其他第三方/原生别名，但绝不触碰 SplashActivity 本体
            try {
                Intent queryIntent = new Intent(Intent.ACTION_MAIN);
                queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                queryIntent.setPackage(pkg);
                List<ResolveInfo> list = pm.queryIntentActivities(queryIntent, PackageManager.MATCH_DISABLED_COMPONENTS);
                if (list != null) {
                    for (ResolveInfo ri : list) {
                        if (ri.activityInfo == null) continue;
                        String actName = ri.activityInfo.name;
                        if (actName == null || actName.equals(targetAlias) || actName.equals(SPLASH_ACTIVITY)) {
                            continue;
                        }
                        ComponentName otherComp = new ComponentName(pkg, actName);
                        int st = pm.getComponentEnabledSetting(otherComp);
                        if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                            pm.setComponentEnabledSetting(
                                    otherComp,
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                    PackageManager.DONT_KILL_APP
                            );
                        }
                    }
                }
            } catch (Throwable ignored) {}

            PLog.i(TAG, "已切换图标/名称组合: [" + iconId + "|" + nameId + "] -> " + targetAlias);
            return true;
        } catch (Throwable t) {
            PLog.e(TAG, "切换图标/名称失败", t);
            return false;
        }
    }

    private static boolean isVivoDevice() {
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase(Locale.ROOT) : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.ROOT) : "";
        return brand.contains("vivo") || brand.contains("iqoo")
                || manufacturer.contains("vivo") || manufacturer.contains("iqoo");
    }

    public static void showIconPickerDialog(Activity activity, Runnable onRefresh) {
        if (activity == null || activity.isFinishing()) return;

        final IconConfig cfg = loadConfig(activity);
        final String[] activePair = resolveActivePair(activity, cfg);
        final String[] selectedIconId = new String[]{ activePair[0] };
        final String[] selectedNameId = new String[]{ activePair[1] };

        boolean isNight = false;
        try {
            isNight = ThemeHelper.INSTANCE.isNightMode();
        } catch (Throwable ignored) {}

        final int bgColor      = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        final int textColor    = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        final int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        final int cardBgColor  = isNight ? Color.parseColor("#252527") : Color.parseColor("#F7F8FA");
        final int strokeColor  = isNight ? Color.parseColor("#38383A") : Color.parseColor("#E5E5EA");
        final int accentColor  = Color.parseColor("#007AFF");
        final int greenColor   = Color.parseColor("#34C759");
        final boolean nightMode = isNight;

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int pad = dp2px(activity, 18f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(bgColor);
        rootBg.setCornerRadius(dp2px(activity, 18f));
        root.setBackground(rootBg);

        TextView title = new TextView(activity);
        title.setText("更换应用图标与名称");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subTitle = new TextView(activity);
        subTitle.setText("切换后返回桌面等待 1~3 秒即可生效（桌面位置可能重排）");
        subTitle.setTextSize(12);
        subTitle.setTextColor(subTextColor);
        subTitle.setGravity(Gravity.CENTER);
        subTitle.setPadding(0, dp2px(activity, 4f), 0, dp2px(activity, 10f));
        root.addView(subTitle);

        // 系统桌面图标重绘提示条（针对 vivo / iQOO 展示精确关闭路径）
        if (cfg.icons.size() > 1) {
            TextView tipView = new TextView(activity);
            if (isVivoDevice()) {
                tipView.setText("vivo / iQOO 若切换后桌面图标未变化：请前往系统「设置 → 桌面、锁屏与壁纸 → 桌面设置 → 图标风格、大小与名称」，下拉到最下方关闭「重绘图标」。");
            } else {
                tipView.setText("提示：若部分系统桌面切换后仍显示原图标，请检查系统主题/桌面设置中的“重绘第三方应用图标”并关闭，或使用下方「建立快捷方式」。");
            }
            tipView.setTextSize(11f);
            tipView.setLineSpacing(dp2px(activity, 2f), 1.0f);
            tipView.setTextColor(isNight ? Color.parseColor("#E6A23C") : Color.parseColor("#B25E00"));
            tipView.setPadding(dp2px(activity, 10f), dp2px(activity, 8f), dp2px(activity, 10f), dp2px(activity, 8f));

            GradientDrawable tipBg = new GradientDrawable();
            tipBg.setColor(isNight ? Color.parseColor("#2E2415") : Color.parseColor("#FFF8E6"));
            tipBg.setCornerRadius(dp2px(activity, 10f));
            tipBg.setStroke(dp2px(activity, 1f), isNight ? Color.parseColor("#59421A") : Color.parseColor("#FFE58F"));
            tipView.setBackground(tipBg);

            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tipLp.bottomMargin = dp2px(activity, 10f);
            root.addView(tipView, tipLp);
        }

        // 名称选择区域（只有存在自定义名称时展示）
        final List<TextView> namePills = new ArrayList<>();
        Runnable refreshNamePills = () -> {
            for (int i = 0; i < cfg.names.size() && i < namePills.size(); i++) {
                NameOption nOpt = cfg.names.get(i);
                TextView pill = namePills.get(i);
                boolean isSel = nOpt.id.equals(selectedNameId[0]);

                GradientDrawable pBg = new GradientDrawable();
                pBg.setCornerRadius(dp2px(activity, 14f));
                if (isSel) {
                    pBg.setColor(accentColor);
                    pill.setTextColor(Color.WHITE);
                    pill.getPaint().setFakeBoldText(true);
                } else {
                    pBg.setColor(cardBgColor);
                    pBg.setStroke(dp2px(activity, 1f), strokeColor);
                    pill.setTextColor(textColor);
                    pill.getPaint().setFakeBoldText(false);
                }
                pill.setBackground(pBg);
            }
        };

        if (cfg.names.size() > 1) {
            TextView tvNameHeader = new TextView(activity);
            tvNameHeader.setText("桌面显示名称");
            tvNameHeader.setTextSize(12);
            tvNameHeader.getPaint().setFakeBoldText(true);
            tvNameHeader.setTextColor(subTextColor);
            tvNameHeader.setPadding(dp2px(activity, 2f), 0, 0, dp2px(activity, 6f));
            root.addView(tvNameHeader);

            LinearLayout nameRow = new LinearLayout(activity);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams nameRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            nameRowLp.bottomMargin = dp2px(activity, 12f);

            for (int i = 0; i < cfg.names.size(); i++) {
                final NameOption nOpt = cfg.names.get(i);
                TextView pill = new TextView(activity);
                pill.setText(nOpt.name);
                pill.setTextSize(12.5f);
                pill.setGravity(Gravity.CENTER);
                pill.setPadding(dp2px(activity, 14f), dp2px(activity, 7f), dp2px(activity, 14f), dp2px(activity, 7f));

                LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                if (i < cfg.names.size() - 1) {
                    pillLp.rightMargin = dp2px(activity, 8f);
                }

                pill.setOnClickListener(v -> {
                    selectedNameId[0] = nOpt.id;
                    refreshNamePills.run();
                });

                namePills.add(pill);
                nameRow.addView(pill, pillLp);
            }
            root.addView(nameRow, nameRowLp);
            refreshNamePills.run();
        }

        TextView tvIconHeader = new TextView(activity);
        tvIconHeader.setText("桌面应用图标");
        tvIconHeader.setTextSize(12);
        tvIconHeader.getPaint().setFakeBoldText(true);
        tvIconHeader.setTextColor(subTextColor);
        tvIconHeader.setPadding(dp2px(activity, 2f), 0, 0, dp2px(activity, 6f));
        root.addView(tvIconHeader);

        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.44f);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        final List<LinearLayout> iconCardViews = new ArrayList<>();
        final List<TextView> iconStatusViews = new ArrayList<>();

        Runnable refreshIconCards = () -> {
            for (int i = 0; i < cfg.icons.size() && i < iconCardViews.size(); i++) {
                IconOption opt = cfg.icons.get(i);
                LinearLayout card = iconCardViews.get(i);
                TextView tvStatus = iconStatusViews.get(i);
                boolean isSel = opt.id.equals(selectedIconId[0]);
                boolean isActive = opt.id.equals(activePair[0]);

                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setCornerRadius(dp2px(activity, 12f));
                if (isSel) {
                    cardBg.setColor(nightMode ? Color.parseColor("#1A3B66") : Color.parseColor("#EBF3FF"));
                    cardBg.setStroke(dp2px(activity, 1.5f), accentColor);
                } else {
                    cardBg.setColor(cardBgColor);
                    cardBg.setStroke(dp2px(activity, 1f), strokeColor);
                }
                card.setBackground(cardBg);

                if (isActive && isSel) {
                    tvStatus.setText("使用中");
                } else if (isSel) {
                    tvStatus.setText("已选中");
                } else {
                    tvStatus.setText("选择");
                }
                tvStatus.getPaint().setFakeBoldText(isSel);
                tvStatus.setTextColor(isSel ? Color.WHITE : accentColor);

                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setCornerRadius(dp2px(activity, 14f));
                badgeBg.setColor(isSel ? (isActive ? greenColor : accentColor)
                        : (nightMode ? Color.parseColor("#2C2C2E") : Color.parseColor("#E5F0FF")));
                tvStatus.setBackground(badgeBg);
            }
        };

        for (IconOption opt : cfg.icons) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp2px(activity, 12f), dp2px(activity, 10f), dp2px(activity, 12f), dp2px(activity, 10f));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardLp.bottomMargin = dp2px(activity, 8f);

            // 图标预览
            int iconSize = dp2px(activity, 44f);
            ImageView iv = new ImageView(activity);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bmp = loadOptionBitmap(activity, opt);
            if (bmp != null) {
                iv.setImageBitmap(getRoundedBitmap(bmp, dp2px(activity, 10f)));
            }
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            ivLp.rightMargin = dp2px(activity, 12f);
            card.addView(iv, ivLp);

            // 标题与说明
            LinearLayout textCol = new LinearLayout(activity);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView tvName = new TextView(activity);
            tvName.setText(opt.name);
            tvName.setTextSize(15);
            tvName.getPaint().setFakeBoldText(true);
            tvName.setTextColor(textColor);
            textCol.addView(tvName);

            TextView tvDesc = new TextView(activity);
            tvDesc.setText(opt.isDefault ? "QQ 原生桌面图标" : "预置自定义主图标");
            tvDesc.setTextSize(11.5f);
            tvDesc.setTextColor(subTextColor);
            tvDesc.setPadding(0, dp2px(activity, 2f), 0, 0);
            textCol.addView(tvDesc);

            card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 状态徽标
            TextView tvStatus = new TextView(activity);
            tvStatus.setTextSize(12);
            tvStatus.setGravity(Gravity.CENTER);
            tvStatus.setPadding(dp2px(activity, 12f), dp2px(activity, 5f), dp2px(activity, 12f), dp2px(activity, 5f));
            card.addView(tvStatus);

            card.setOnClickListener(v -> {
                selectedIconId[0] = opt.id;
                refreshIconCards.run();
            });

            iconCardViews.add(card);
            iconStatusViews.add(tvStatus);
            listContainer.addView(card, cardLp);
        }
        refreshIconCards.run();

        scroll.addView(listContainer);
        root.addView(scroll);

        // 相册建立快捷方式独立入口按钮
        if (Build.VERSION.SDK_INT >= 26) {
            Button pinBtn = new Button(activity);
            pinBtn.setText("建立桌面快捷方式");
            pinBtn.setTextSize(13);
            pinBtn.setTextColor(accentColor);
            pinBtn.setAllCaps(false);
            GradientDrawable pinBg = new GradientDrawable();
            pinBg.setColor(isNight ? Color.parseColor("#252527") : Color.parseColor("#F0F5FF"));
            pinBg.setCornerRadius(dp2px(activity, 10f));
            pinBtn.setBackground(pinBg);

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 38f)
            );
            pLp.topMargin = dp2px(activity, 6f);
            pinBtn.setOnClickListener(v -> {
                dialog.dismiss();
                pickImageForPinnedShortcut(activity);
            });
            root.addView(pinBtn, pLp);
        }

        // 底部动作按钮行（取消 + 应用更改）
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 10f), 0, 0);

        Button closeBtn = new Button(activity);
        closeBtn.setText("取消");
        closeBtn.setTextSize(13);
        closeBtn.setTextColor(subTextColor);
        closeBtn.setAllCaps(false);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(cardBgColor);
        closeBg.setCornerRadius(dp2px(activity, 10f));
        closeBtn.setBackground(closeBg);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1f);
        closeLp.rightMargin = dp2px(activity, 8f);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(closeBtn, closeLp);

        Button applyBtn = new Button(activity);
        applyBtn.setText("应用更改");
        applyBtn.setTextSize(13);
        applyBtn.setTextColor(Color.WHITE);
        applyBtn.setAllCaps(false);
        GradientDrawable applyBg = new GradientDrawable();
        applyBg.setColor(accentColor);
        applyBg.setCornerRadius(dp2px(activity, 10f));
        applyBtn.setBackground(applyBg);
        applyBtn.setOnClickListener(v -> {
            if (selectedIconId[0].equals(activePair[0]) && selectedNameId[0].equals(activePair[1])) {
                dialog.dismiss();
                return;
            }
            boolean ok = switchCombination(activity, cfg, selectedIconId[0], selectedNameId[0]);
            dialog.dismiss();
            if (ok) {
                IconOption ic = findIconById(cfg, selectedIconId[0]);
                NameOption nm = findNameById(cfg, selectedNameId[0]);
                String desc = (ic != null ? ic.name : "默认图标")
                        + (nm != null && !nm.isDefault ? (" / " + nm.name) : "");
                ToastHelper.show(activity, "已切换为「" + desc + "」，返回桌面稍等片刻即可生效");
                if (onRefresh != null) onRefresh.run();
            } else {
                ToastHelper.show(activity, "切换失败，请查看运行日志");
            }
        });
        btnRow.addView(applyBtn, new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1.3f));

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static Bitmap loadOptionBitmap(Context context, IconOption opt) {
        if (!opt.isDefault && opt.assetPath != null && !opt.assetPath.isEmpty()) {
            Bitmap bmp = PatchAssetHelper.getBitmap(context, opt.assetPath);
            if (bmp != null) return bmp;
        }
        try {
            Drawable d = context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
            return drawableToBitmap(d);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int w = Math.max(drawable.getIntrinsicWidth(), 144);
        int h = Math.max(drawable.getIntrinsicHeight(), 144);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bmp;
    }

    private static Bitmap getRoundedBitmap(Bitmap src, int radiusPx) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap squared = Bitmap.createBitmap(src, (src.getWidth() - size) / 2, (src.getHeight() - size) / 2, size, size);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Rect rect = new Rect(0, 0, size, size);
        canvas.drawRoundRect(new RectF(rect), radiusPx, radiusPx, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(squared, rect, rect, paint);
        return output;
    }

    @SuppressWarnings("deprecation")
    private static void pickImageForPinnedShortcut(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) {
            ToastHelper.show(activity, "当前系统版本不支持创建桌面快捷方式");
            return;
        }
        try {
            FragmentManager fm = activity.getFragmentManager();
            Fragment existing = fm.findFragmentByTag("zzz_icon_picker_frag");
            if (existing != null) {
                fm.beginTransaction().remove(existing).commitAllowingStateLoss();
            }
            ImagePickerFragment frag = new ImagePickerFragment();
            fm.beginTransaction().add(frag, "zzz_icon_picker_frag").commitAllowingStateLoss();
            fm.executePendingTransactions();
            frag.launchPicker();
        } catch (Throwable t) {
            PLog.e(TAG, "拉起相册异常", t);
            ToastHelper.show(activity, "打开相册失败: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    public static class ImagePickerFragment extends Fragment {
        private static final int REQ_PICK_IMAGE = 10921;

        public void launchPicker() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "选择快捷方式图标"), REQ_PICK_IMAGE);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            Activity act = getActivity();
            try {
                if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null && act != null) {
                    createPinnedShortcutFromUri(act, data.getData());
                }
            } finally {
                try {
                    if (act != null) {
                        act.getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void createPinnedShortcutFromUri(Activity activity, Uri uri) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Bitmap scaled = decodeSampledSquareBitmap(activity, uri, 256);
            if (scaled == null) {
                ToastHelper.show(activity, "无法解析所选图片");
                return;
            }

            String defaultLabel = "QQ";
            try {
                CharSequence lbl = activity.getApplicationInfo().loadLabel(activity.getPackageManager());
                if (lbl != null && lbl.length() > 0) {
                    defaultLabel = lbl.toString();
                }
            } catch (Throwable ignored) {}

            final String fallbackLabel = defaultLabel;
            SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "创建桌面快捷方式")
                    .setSubTitle("确认图标与桌面显示名称");

            ImageView preview = new ImageView(activity);
            int pSize = dp2px(activity, 72f);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(pSize, pSize);
            pLp.gravity = Gravity.CENTER_HORIZONTAL;
            pLp.bottomMargin = dp2px(activity, 12f);
            preview.setLayoutParams(pLp);
            preview.setImageBitmap(getRoundedBitmap(scaled, dp2px(activity, 16f)));

            builder.addCustomView(preview)
                    .addInput("快捷方式名称", fallbackLabel, true, false, null)
                    .setPositiveButton("创建", dialog -> {
                        String customLabel = builder.getInputValue(0);
                        if (customLabel.isEmpty()) customLabel = fallbackLabel;
                        dialog.dismiss();
                        requestPinShortcut(activity, scaled, customLabel);
                    })
                    .show();
        } catch (Throwable t) {
            PLog.e(TAG, "创建桌面快捷方式异常", t);
            ToastHelper.show(activity, "创建快捷方式失败: " + t.getMessage());
        }
    }

    // 分两步按 inSampleSize 降采样解码相册图片，防止高像素原图 OOM
    private static Bitmap decodeSampledSquareBitmap(Activity activity, Uri uri, int targetSize) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

            int inSample = 1;
            int halfW = opts.outWidth / 2;
            int halfH = opts.outHeight / 2;
            while ((halfW / inSample) >= targetSize && (halfH / inSample) >= targetSize) {
                inSample *= 2;
            }

            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = inSample;
            Bitmap raw;
            try (InputStream is2 = activity.getContentResolver().openInputStream(uri)) {
                if (is2 == null) return null;
                raw = BitmapFactory.decodeStream(is2, null, decodeOpts);
            }
            if (raw == null) return null;

            int size = Math.min(raw.getWidth(), raw.getHeight());
            Bitmap square = Bitmap.createBitmap(raw, (raw.getWidth() - size) / 2, (raw.getHeight() - size) / 2, size, size);
            Bitmap scaled = Bitmap.createScaledBitmap(square, targetSize, targetSize, true);
            if (square != raw && square != scaled) square.recycle();
            if (raw != scaled) raw.recycle();
            return scaled;
        } catch (Throwable t) {
            PLog.w(TAG, "降采样解码图片异常: " + t.getMessage());
            return null;
        }
    }

    private static void requestPinShortcut(Activity activity, Bitmap iconBmp, String label) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ShortcutManager sm = activity.getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                ToastHelper.show(activity, "当前桌面不支持创建快捷方式");
                return;
            }

            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.setClassName(activity.getPackageName(), SPLASH_ACTIVITY);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            String shortcutId = "zzz_custom_shortcut_" + System.currentTimeMillis();
            ShortcutInfo info = new ShortcutInfo.Builder(activity, shortcutId)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithBitmap(iconBmp))
                    .setIntent(launchIntent)
                    .build();

            sm.requestPinShortcut(info, null);
            ToastHelper.show(activity, "已尝试请求创建桌面快捷方式");
        } catch (Throwable t) {
            PLog.e(TAG, "请求固定快捷方式异常", t);
            ToastHelper.show(activity, "创建快捷方式失败: " + t.getMessage());
        }
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
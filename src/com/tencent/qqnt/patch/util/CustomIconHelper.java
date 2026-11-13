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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import me.yxp.qfun.utils.ui.ThemeHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CustomIconHelper {

    private static final String TAG = "CustomIcon";
    private static final String DEFAULT_ALIAS = "com.tencent.mobileqq.activity.ZzzDefaultSplashActivity";
    private static final String SPLASH_ACTIVITY = "com.tencent.mobileqq.activity.SplashActivity";

    public static class IconOption {
        public final String name;
        public final String alias;
        public final String assetPath;
        public final boolean isDefault;

        public IconOption(String name, String alias, String assetPath, boolean isDefault) {
            this.name = name;
            this.alias = alias;
            this.assetPath = assetPath;
            this.isDefault = isDefault;
        }
    }

    public static class IconConfig {
        public String defaultAlias = DEFAULT_ALIAS;
        public final List<IconOption> options = new ArrayList<>();
    }

    public static IconConfig loadConfig(Context context) {
        IconConfig cfg = new IconConfig();
        if (context == null) {
            cfg.options.add(new IconOption("默认图标", cfg.defaultAlias, null, true));
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

                cfg.options.add(new IconOption("默认图标", cfg.defaultAlias, null, true));

                JSONArray arr = root.optJSONArray("icons");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        String name = item.optString("name", "图标 " + (i + 1));
                        String alias = item.optString("alias", "");
                        String asset = item.optString("asset", "");
                        if (!alias.isEmpty()) {
                            cfg.options.add(new IconOption(name, alias, asset, false));
                        }
                    }
                }
                return cfg;
            }
        } catch (Throwable t) {
            PLog.w(TAG, "读取 zzz_icons.json 异常: " + t.getMessage());
        }

        cfg.options.add(new IconOption("默认图标", cfg.defaultAlias, null, true));
        return cfg;
    }

    public static String getCurrentIconName(Context context) {
        if (context == null) return "默认图标";
        IconConfig cfg = loadConfig(context);
        IconOption active = resolveActiveOption(context, cfg);
        return active != null ? active.name : "默认图标";
    }

    private static IconOption resolveActiveOption(Context context, IconConfig cfg) {
        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();

        for (IconOption opt : cfg.options) {
            if (opt.isDefault) continue;
            try {
                int state = pm.getComponentEnabledSetting(new ComponentName(pkg, opt.alias));
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return opt;
                }
            } catch (Throwable ignored) {}
        }
        return cfg.options.isEmpty() ? null : cfg.options.get(0);
    }

    public static boolean switchIcon(Context context, IconConfig cfg, IconOption target) {
        if (context == null || cfg == null || target == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();

            // 1. 先启用目标别名（保证任何时刻都至少有一个 LAUNCHER 组件可用）
            ComponentName targetComp = new ComponentName(pkg, target.alias);
            pm.setComponentEnabledSetting(
                    targetComp,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );

            // 2. 再禁用其余别名
            for (IconOption opt : cfg.options) {
                if (opt.alias.equals(target.alias)) continue;
                try {
                    pm.setComponentEnabledSetting(
                            new ComponentName(pkg, opt.alias),
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
                        if (actName == null || actName.equals(target.alias) || actName.equals(SPLASH_ACTIVITY)) {
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

            PLog.i(TAG, "已切换应用图标为: " + target.name + " (" + target.alias + ")");
            return true;
        } catch (Throwable t) {
            PLog.e(TAG, "切换图标失败", t);
            return false;
        }
    }

    public static void showIconPickerDialog(Activity activity, Runnable onRefresh) {
        if (activity == null || activity.isFinishing()) return;

        final IconConfig cfg = loadConfig(activity);
        final IconOption activeOpt = resolveActiveOption(activity, cfg);
        final String activeAlias = activeOpt != null ? activeOpt.alias : cfg.defaultAlias;

        boolean isNight = false;
        try {
            isNight = ThemeHelper.INSTANCE.isNightMode();
        } catch (Throwable ignored) {}

        int bgColor      = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int textColor    = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        int cardBgColor  = isNight ? Color.parseColor("#252527") : Color.parseColor("#F7F8FA");
        int strokeColor  = isNight ? Color.parseColor("#38383A") : Color.parseColor("#E5E5EA");
        int accentColor  = Color.parseColor("#007AFF");
        int greenColor   = Color.parseColor("#34C759");

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
        title.setText("更换应用图标");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subTitle = new TextView(activity);
        subTitle.setText(cfg.options.size() <= 1
                ? "未在 icons/ 目录检测到自定义图标，当前仅默认图标可用"
                : "切换后返回桌面等待 1~3 秒即可生效（桌面位置可能重排）");
        subTitle.setTextSize(12);
        subTitle.setTextColor(subTextColor);
        subTitle.setGravity(Gravity.CENTER);
        subTitle.setPadding(0, dp2px(activity, 4f), 0, dp2px(activity, 12f));
        root.addView(subTitle);

        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.56f);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        for (IconOption opt : cfg.options) {
            boolean isSelected = opt.alias.equals(activeAlias);

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp2px(activity, 12f), dp2px(activity, 10f), dp2px(activity, 12f), dp2px(activity, 10f));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp2px(activity, 12f));
            if (isSelected) {
                cardBg.setColor(isNight ? Color.parseColor("#1A3B66") : Color.parseColor("#EBF3FF"));
                cardBg.setStroke(dp2px(activity, 1.5f), accentColor);
            } else {
                cardBg.setColor(cardBgColor);
                cardBg.setStroke(dp2px(activity, 1f), strokeColor);
            }
            card.setBackground(cardBg);

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
            tvStatus.setText(isSelected ? "使用中" : "切换");
            tvStatus.setTextSize(12);
            tvStatus.getPaint().setFakeBoldText(isSelected);
            tvStatus.setTextColor(isSelected ? Color.WHITE : accentColor);
            tvStatus.setGravity(Gravity.CENTER);
            tvStatus.setPadding(dp2px(activity, 12f), dp2px(activity, 5f), dp2px(activity, 12f), dp2px(activity, 5f));

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp2px(activity, 14f));
            badgeBg.setColor(isSelected ? greenColor : (isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#E5F0FF")));
            tvStatus.setBackground(badgeBg);
            card.addView(tvStatus);

            card.setOnClickListener(v -> {
                if (isSelected) {
                    dialog.dismiss();
                    return;
                }
                boolean ok = switchIcon(activity, cfg, opt);
                dialog.dismiss();
                if (ok) {
                    ToastHelper.show(activity, "已切换为「" + opt.name + "」，返回桌面稍等片刻即可生效");
                    if (onRefresh != null) onRefresh.run();
                } else {
                    ToastHelper.show(activity, "切换图标失败，请查看运行日志");
                }
            });

            listContainer.addView(card, cardLp);
        }

        scroll.addView(listContainer);
        root.addView(scroll);

        // 底部按钮行（相册选图快捷方式 + 关闭）
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 10f), 0, 0);

        if (Build.VERSION.SDK_INT >= 26) {
            Button pinBtn = new Button(activity);
            pinBtn.setText("相册选图建快捷方式");
            pinBtn.setTextSize(13);
            pinBtn.setTextColor(Color.WHITE);
            pinBtn.setAllCaps(false);
            GradientDrawable pinBg = new GradientDrawable();
            pinBg.setColor(accentColor);
            pinBg.setCornerRadius(dp2px(activity, 10f));
            pinBtn.setBackground(pinBg);

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1.3f);
            pLp.rightMargin = dp2px(activity, 8f);
            pinBtn.setOnClickListener(v -> {
                dialog.dismiss();
                pickImageForPinnedShortcut(activity);
            });
            btnRow.addView(pinBtn, pLp);
        }

        Button closeBtn = new Button(activity);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(13);
        closeBtn.setTextColor(subTextColor);
        closeBtn.setAllCaps(false);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(cardBgColor);
        closeBg.setCornerRadius(dp2px(activity, 10f));
        closeBtn.setBackground(closeBg);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(closeBtn, new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1f));

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
            PLog.e(TAG, "拉起相册选图异常", t);
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
        try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            Bitmap raw = BitmapFactory.decodeStream(is);
            if (raw == null) {
                ToastHelper.show(activity, "无法解析所选图片");
                return;
            }
            int size = Math.min(raw.getWidth(), raw.getHeight());
            Bitmap square = Bitmap.createBitmap(raw, (raw.getWidth() - size) / 2, (raw.getHeight() - size) / 2, size, size);
            Bitmap scaled = Bitmap.createScaledBitmap(square, 256, 256, true);

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
                    .setShortLabel("QQ")
                    .setLongLabel("QQ")
                    .setIcon(Icon.createWithBitmap(scaled))
                    .setIntent(launchIntent)
                    .build();

            sm.requestPinShortcut(info, null);
            ToastHelper.show(activity, "已请求创建桌面快捷方式（如系统拦截请授予“桌面快捷方式”权限）");
        } catch (Throwable t) {
            PLog.e(TAG, "创建桌面快捷方式异常", t);
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
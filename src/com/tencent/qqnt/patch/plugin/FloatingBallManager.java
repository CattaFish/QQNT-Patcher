package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PatchAssetHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FloatingBallManager {

    private static volatile boolean sRegistered = false;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static PopupWindow sPopupWindow = null;
    private static View sFloatBtn = null;
    private static int sLastX = -1;
    private static int sLastY = -1;

    public static void init(Context context) {
        if (sRegistered) return;
        sRegistered = true;
        AppContext.init(context);
    }

    public static Activity resolveCurrentActivity() {
        return AppContext.getCurrentActivity();
    }

    private static boolean isAIOActivity(Activity act) {
        if (act == null) return false;
        String name = act.getClass().getName();
        return !name.contains("Setting") && !name.contains("Preference") && !name.contains("Plugin") && !name.contains("Clean");
    }

    public static void onAIODelegateShow(Object delegate) {
        if (delegate == null) return;
        sMainHandler.post(() -> {
            Activity act = resolveCurrentActivity();
            if (act != null && AioSessionTracker.isInAIO() && isAIOActivity(act)) {
                hideView();
                showView(act);
            }
        });
    }

    public static void onAIODelegateHide() {
        sMainHandler.post(FloatingBallManager::hideView);
    }

    private static void showView(Activity activity) {
        if (activity == null || !ConfigManager.isFloatingBallEnabled() || !AioSessionTracker.isInAIO()) return;
        if (sPopupWindow != null && sPopupWindow.isShowing()) return;

        try {
            int size = dp2px(activity, 44f);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;

            SharedPreferences sp = activity.getSharedPreferences("zzz_floating_pref", Context.MODE_PRIVATE);
            sLastX = sp.getInt("float_x", screenW - size - dp2px(activity, 16f));
            sLastY = sp.getInt("float_y", (int) (screenH * 0.62f));

            ImageView imageView = new ImageView(activity);
            Bitmap bmp = PatchAssetHelper.getBitmap(activity, "zzz_icon.png");
            if (bmp != null) imageView.setImageBitmap(bmp);

            imageView.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            sFloatBtn = imageView;

            sPopupWindow = new PopupWindow(imageView, size, size, false);
            sPopupWindow.setOutsideTouchable(false);
            sPopupWindow.setFocusable(false);
            sPopupWindow.setElevation(0f);

            setupTouchListener(activity, sp);

            View decor = activity.getWindow().getDecorView();
            decor.post(() -> {
                try {
                    if (AioSessionTracker.isInAIO() && sPopupWindow != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        sPopupWindow.showAtLocation(decor, Gravity.NO_GRAVITY, sLastX, sLastY);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static void hideView() {
        try {
            if (sPopupWindow != null) {
                sPopupWindow.dismiss();
                sPopupWindow = null;
            }
            sFloatBtn = null;
        } catch (Throwable ignored) {}
    }

    private static void setupTouchListener(Activity activity, SharedPreferences sp) {
        if (sFloatBtn == null) return;
        sFloatBtn.setOnTouchListener(new View.OnTouchListener() {
            private float touchStartX, touchStartY;
            private int initialTouchX, initialTouchY;
            private boolean isDragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        initialTouchX = sLastX;
                        initialTouchY = sLastY;
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStartX;
                        float dy = event.getRawY() - touchStartY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true;
                        if (isDragging && sPopupWindow != null) {
                            sLastX = (int) (initialTouchX + dx);
                            sLastY = (int) (initialTouchY + dy);
                            sPopupWindow.update(sLastX, sLastY, -1, -1);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isDragging) {
                            sp.edit().putInt("float_x", sLastX).putInt("float_y", sLastY).apply();
                        } else {
                            showActionMenu(activity);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    public static void refreshVisibility() {
        Activity act = resolveCurrentActivity();
        if (act != null) {
            act.runOnUiThread(() -> {
                if (ConfigManager.isFloatingBallEnabled() && AioSessionTracker.isInAIO() && isAIOActivity(act)) {
                    showView(act);
                } else {
                    hideView();
                }
            });
        }
    }

    private static void showActionMenu(Activity activity) {
        if (activity == null) return;
        AioSessionTracker.refreshFromCurrentDelegate();

        List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(activity);
        List<ActionItem> actions = new ArrayList<>();
        for (PluginManager.PluginItem p : plugins) {
            if (p.isEnabled && p.menuItems != null) {
                for (Map.Entry<String, String> entry : p.menuItems.entrySet()) {
                    actions.add(new ActionItem(p.id, p.name, entry.getKey(), entry.getValue()));
                }
            }
        }

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(activity, 20f);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(activity, 20f));
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("脚本快捷菜单");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#1D1D1F"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 15f));
        root.addView(title);

        if (actions.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("当前已开启的脚本暂未注册菜单\n(请在设置中开启包含 addItem 的脚本)");
            empty.setTextSize(14);
            empty.setTextColor(Color.GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp2px(activity, 10f), 0, dp2px(activity, 15f));
            root.addView(empty);
        } else {
            for (ActionItem action : actions) {
                Button btn = new Button(activity);
                btn.setText(action.actionName + " (" + action.pluginName + ")");
                btn.setTextSize(15);
                btn.setTextColor(Color.WHITE);
                btn.setAllCaps(false);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#007AFF"));
                btnBg.setCornerRadius(dp2px(activity, 12f));
                btn.setBackground(btnBg);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 46f));
                lp.bottomMargin = dp2px(activity, 10f);

                btn.setOnClickListener(v -> {
                    dialog.dismiss();
                    int cType = AioSessionTracker.getActiveChatType();
                    if (cType == 0) cType = 2;
                    String peerUin = AioSessionTracker.getActivePeerUin();
                    PluginManager.invokePluginMenu(action.pluginId, action.callback, cType, peerUin, action.actionName);
                });
                root.addView(btn, lp);
            }
        }

        Button reloadBtn = new Button(activity);
        reloadBtn.setText("重新扫描全部脚本");
        reloadBtn.setTextSize(14);
        reloadBtn.setTextColor(Color.parseColor("#34C759"));
        reloadBtn.setAllCaps(false);

        GradientDrawable rBg = new GradientDrawable();
        rBg.setColor(Color.parseColor("#F2F2F7"));
        rBg.setCornerRadius(dp2px(activity, 12f));
        reloadBtn.setBackground(rBg);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f));
        reloadBtn.setOnClickListener(v -> {
            dialog.dismiss();
            PluginManager.reloadAll(activity);
            Toast.makeText(activity, "已提交重新扫描指令", Toast.LENGTH_SHORT).show();
        });
        root.addView(reloadBtn, rLp);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.82);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class ActionItem {
        String pluginId;
        String pluginName;
        String actionName;
        String callback;
        ActionItem(String pId, String pName, String aName, String cb) {
            this.pluginId = pId;
            this.pluginName = pName;
            this.actionName = aName;
            this.callback = cb;
        }
    }
}
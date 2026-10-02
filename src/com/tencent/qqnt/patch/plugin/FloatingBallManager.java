package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.PLog;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FloatingBallManager {

    private static final String TAG = "FloatingBall";
    private static volatile boolean sRegistered = false;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<Object> sCurrentAIODelegate = null;

    public static volatile int sActiveChatType = 0;
    public static volatile String sActivePeerUid = "";
    public static volatile String sActivePeerUin = "";
    public static volatile String sActivePeerName = "";
    public static volatile boolean sInAIO = false;

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
        sCurrentAIODelegate = new WeakReference<>(delegate);
        sInAIO = true;

        refreshContactFromDelegate(delegate);

        // 核心：分发 chatInterface 事件给所有正在运行的脚本
        PluginManager.dispatchChatInterface(sActiveChatType, sActivePeerUin, sActivePeerName);

        sMainHandler.post(() -> {
            Activity act = resolveCurrentActivity();
            if (act != null && sInAIO && isAIOActivity(act)) {
                hideView();
                showView(act);
            }
        });
    }

    public static void onAIODelegateHide() {
        sInAIO = false;
        sActiveChatType = 0;
        sActivePeerUid = "";
        sActivePeerUin = "";
        sActivePeerName = "";
        sCurrentAIODelegate = null;
        sMainHandler.post(FloatingBallManager::hideView);
    }

    public static void onAIOMsgItemBind(Object msgRecordObj) {}

    public static void refreshContactFromDelegate(Object delegate) {
        if (delegate == null) return;
        try {
            Class<?> clz = delegate.getClass();
            int chatType = 0;
            long peerUin = 0L;
            String peerId = "";
            String chatName = "";

            try {
                Field fD = clz.getDeclaredField("D");
                fD.setAccessible(true);
                peerUin = fD.getLong(delegate);

                Field fF = clz.getDeclaredField("F");
                fF.setAccessible(true);
                chatType = fF.getInt(delegate);

                Field fC = clz.getDeclaredField("C");
                fC.setAccessible(true);
                Object cVal = fC.get(delegate);
                if (cVal != null) peerId = cVal.toString();

                Field fE = clz.getDeclaredField("E");
                fE.setAccessible(true);
                Object eVal = fE.get(delegate);
                if (eVal != null) chatName = eVal.toString();
            } catch (Throwable ignored) {}

            if (peerUin == 0L || chatType == 0) {
                try {
                    Field fContainer = clz.getDeclaredField("d");
                    fContainer.setAccessible(true);
                    Object container = fContainer.get(delegate);
                    if (container != null) {
                        Method mGetIntent = container.getClass().getMethod("getIntent");
                        Intent intent = (Intent) mGetIntent.invoke(container);
                        if (intent != null) {
                            long iUin = intent.getLongExtra("key_peerUin", 0L);
                            int iType = intent.getIntExtra("key_chat_type", 0);
                            String iId = intent.getStringExtra("key_peerId");
                            String iName = intent.getStringExtra("key_chat_name");

                            if (iUin > 0L) peerUin = iUin;
                            if (iType != 0) chatType = iType;
                            if (iId != null && !iId.isEmpty()) peerId = iId;
                            if (iName != null && !iName.isEmpty()) chatName = iName;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (chatType != 0) {
                sActiveChatType = chatType;
            } else {
                sActiveChatType = (peerUin > 0L && String.valueOf(peerUin).length() >= 6) ? 2 : 1;
            }

            sActivePeerUid = peerId;
            sActivePeerName = chatName;

            if (peerUin > 0L) {
                sActivePeerUin = String.valueOf(peerUin);
            } else if (!peerId.isEmpty()) {
                if (sActiveChatType == 1 && peerId.startsWith("u_")) {
                    sActivePeerUin = MsgSender.getUinFromUid(peerId);
                } else {
                    sActivePeerUin = peerId;
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析当前界面会话参数异常", t);
        }
    }

    private static void showView(Activity activity) {
        if (activity == null || !ConfigManager.isFloatingBallEnabled() || !sInAIO) return;
        if (sPopupWindow != null && sPopupWindow.isShowing()) return;

        try {
            int size = dp2px(activity, 44f);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;

            SharedPreferences sp = activity.getSharedPreferences("zzz_floating_pref", Context.MODE_PRIVATE);
            sLastX = sp.getInt("float_x", screenW - size - dp2px(activity, 16f));
            sLastY = sp.getInt("float_y", (int) (screenH * 0.62f));

            ImageView imageView = new ImageView(activity);
            Bitmap bmp = com.tencent.qqnt.patch.PatchAssetHelper.getBitmap(activity, "zzz_icon.png");
            if (bmp != null) imageView.setImageBitmap(bmp);

            imageView.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            sFloatBtn = imageView;

            sPopupWindow = new PopupWindow(imageView, size, size, false);
            sPopupWindow.setOutsideTouchable(false);
            sPopupWindow.setFocusable(false);
            sPopupWindow.setElevation(0f);

            setupTouchListener(activity, sp, size);

            View decor = activity.getWindow().getDecorView();
            decor.post(() -> {
                try {
                    if (sInAIO && sPopupWindow != null && !activity.isFinishing() && !activity.isDestroyed()) {
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

    private static void setupTouchListener(Activity activity, SharedPreferences sp, int size) {
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
                if (ConfigManager.isFloatingBallEnabled() && sInAIO && isAIOActivity(act)) {
                    showView(act);
                } else {
                    hideView();
                }
            });
        }
    }

    private static void showActionMenu(Activity activity) {
        if (activity == null) return;
        if (sCurrentAIODelegate != null && sCurrentAIODelegate.get() != null) {
            refreshContactFromDelegate(sCurrentAIODelegate.get());
        }

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
                    int cType = sActiveChatType != 0 ? sActiveChatType : 2;
                    String peerUin = sActivePeerUin;
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

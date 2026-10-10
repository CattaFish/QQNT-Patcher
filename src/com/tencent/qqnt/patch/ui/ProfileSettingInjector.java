package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.modules.chat.QQVersionModule;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

public class ProfileSettingInjector {

    private static final String TAG = "ProfileSettingInjector";
    private static final String TAG_VERSION_CARD = "zzz_qq_version_card_item";

    /**
     * 场景 1: ProfileCardMoreActivity 全动态挂载 (左右/上下标准 16dp 独立分离卡片)
     */
    public static void injectProfileCardMore(Object activityObj) {
        if (!(activityObj instanceof Activity)) return;

        Activity activity = (Activity) activityObj;
        try {
            // 1. 全动态定位卡片容器
            View sampleItem = findViewByClassName(activity.getWindow().getDecorView(), "QUISingleLineListItem");
            if (sampleItem == null) {
                sampleItem = findViewByClassName(activity.getWindow().getDecorView(), "FormSimpleItem");
            }
            if (sampleItem == null || !(sampleItem.getParent() instanceof ViewGroup)) return;

            ViewGroup parent = (ViewGroup) sampleItem.getParent();
            View existingCard = parent.findViewWithTag(TAG_VERSION_CARD);

            // 模块关闭检查：若已关闭且界面上有卡片则立刻移除
            if (!ConfigManager.isModuleEnabled("qq_version", false)) {
                if (existingCard != null) {
                    parent.removeView(existingCard);
                }
                return;
            }

            // 2. 全动态获取目标 UIN
            String targetUin = extractTargetUinFromActivity(activity);
            if (targetUin == null || targetUin.isEmpty()) return;

            // 3. 反查版本号
            String versionText = QQVersionModule.getVersionByUin(targetUin);

            // 标准独立卡片边距: 左右各 16dp, 顶部 16dp (与设置标题栏分离), 底部 12dp
            int hMargin = dp2px(activity, 16f);
            int topMargin = dp2px(activity, 16f);
            int bottomMargin = dp2px(activity, 12f);

            // 4. 防重检查: 如果已存在则直接更新文字与边距
            if (existingCard != null) {
                updateCardConfig(activity, existingCard, versionText, targetUin);
                applyCardMargins(existingCard, parent, hMargin, topMargin, bottomMargin);
                return;
            }

            // 5. 构建原生独立卡片
            ClassLoader cl = activity.getClassLoader();
            Class<?> itemClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUISingleLineListItem");
            Class<?> bgTypeClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUIListItemBackgroundType");
            Class<?> styleClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUIListItemStyle");

            Constructor<?> ctor = itemClz.getConstructor(Context.class);
            View cardItem = (View) ctor.newInstance(activity);
            cardItem.setTag(TAG_VERSION_CARD);

            // 设置四周全圆角与卡片样式
            Object allRound = Enum.valueOf((Class<Enum>) bgTypeClz, "AllRound");
            Method setBgMethod = itemClz.getMethod("setBackgroundType", bgTypeClz);
            setBgMethod.invoke(cardItem, allRound);

            Object cardStyle = Enum.valueOf((Class<Enum>) styleClz, "Card");
            Method setStyleMethod = itemClz.getMethod("setStyle", styleClz);
            setStyleMethod.invoke(cardItem, cardStyle);

            updateCardConfig(activity, cardItem, versionText, targetUin);

            // 赋予真正的卡片四向外边距 (彻底告别贴顶栏和撑满屏幕)
            applyCardMargins(cardItem, parent, hMargin, topMargin, bottomMargin);

            // 插入最上方 (第 0 位)
            parent.addView(cardItem, 0);
            PLog.i(TAG, "已在 ProfileCardMoreActivity 挂载独立悬浮版本卡片: " + versionText);

        } catch (Throwable t) {
            PLog.e(TAG, "injectProfileCardMore 异常", t);
        }
    }

    /**
     * 场景 2: MemberSettingFragment 全动态挂载
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void injectMemberSettingGroups(Object fragment, Object groupsListObj) {
        if (fragment == null || !(groupsListObj instanceof ArrayList)) return;
        if (!ConfigManager.isModuleEnabled("qq_version", false)) return;

        ArrayList groupsList = (ArrayList) groupsListObj;
        try {
            String memberUin = extractMemberUinFromFragment(fragment);
            if (memberUin == null || memberUin.isEmpty()) return;

            String versionText = QQVersionModule.getVersionByUin(memberUin);

            ClassLoader cl = fragment.getClass().getClassLoader();
            Activity activity = null;
            try {
                activity = (Activity) fragment.getClass().getMethod("getActivity").invoke(fragment);
            } catch (Throwable ignored) {}

            final Activity finalAct = activity;
            Object textItem = NativeSettingHelper.createClickable(
                    cl, "QQ 版本", versionText, false, false,
                    v -> handleCardClick(finalAct, memberUin, versionText)
            );

            if (textItem != null) {
                Object versionGroup = NativeSettingHelper.createGroup(
                        cl, "", "", Collections.singletonList(textItem)
                );
                if (versionGroup != null) {
                    groupsList.add(0, versionGroup);
                    PLog.i(TAG, "已在 MemberSettingFragment 挂载独立 Group: " + versionText);
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "injectMemberSettingGroups 异常", t);
        }
    }

    // ========================== 边距与样式工具 ==========================

    private static void applyCardMargins(View cardView, ViewGroup parent, int hMargin, int topMargin, int bottomMargin) {
        ViewGroup.MarginLayoutParams lp;
        if (parent instanceof LinearLayout) {
            lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (parent instanceof RelativeLayout) {
            lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            lp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.leftMargin = hMargin;
        lp.rightMargin = hMargin;
        lp.topMargin = topMargin;
        lp.bottomMargin = bottomMargin;
        cardView.setLayoutParams(lp);
    }

    private static String extractTargetUinFromActivity(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent != null && intent.hasExtra("AllInOne")) {
                Object aio = intent.getParcelableExtra("AllInOne");
                if (aio != null) {
                    Field fUin = aio.getClass().getField("uin");
                    String uin = (String) fUin.get(aio);
                    if (uin != null && !uin.isEmpty()) return uin;
                }
            }
            if (intent != null && intent.hasExtra("uin")) {
                String uin = intent.getStringExtra("uin");
                if (uin != null && !uin.isEmpty()) return uin;
            }
        } catch (Throwable ignored) {}

        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("AllInOne")) {
                    f.setAccessible(true);
                    Object aio = f.get(activity);
                    if (aio != null) {
                        Field fUin = aio.getClass().getField("uin");
                        String uin = (String) fUin.get(aio);
                        if (uin != null && !uin.isEmpty()) return uin;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractMemberUinFromFragment(Object fragment) {
        try {
            Method getArgsM = fragment.getClass().getMethod("getArguments");
            Bundle args = (Bundle) getArgsM.invoke(fragment);
            if (args != null) {
                String[] candidateKeys = new String[]{"memberUin", "member_uin", "troop_member_uin", "uin"};
                for (String k : candidateKeys) {
                    if (args.containsKey(k)) {
                        String v = args.getString(k);
                        if (v != null && v.matches("[1-9]\\d{4,12}")) return v;
                    }
                }
                for (String k : args.keySet()) {
                    Object val = args.get(k);
                    if (val instanceof String && ((String) val).matches("[1-9]\\d{4,12}")) {
                        return (String) val;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            for (Field f : fragment.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("ViewModel")) {
                    f.setAccessible(true);
                    Object vm = f.get(fragment);
                    if (vm != null) {
                        String uin = scanObjectForTroopMemberCard(vm);
                        if (uin != null) return uin;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String scanObjectForTroopMemberCard(Object obj) {
        if (obj == null) return null;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val == null) continue;
                if (val.getClass().getName().contains("TroopMemberCard")) {
                    Field fUin = val.getClass().getField("memberUin");
                    return (String) fUin.get(val);
                }
            }
            for (Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    Object val = m.invoke(obj);
                    if (val != null && val.getClass().getName().contains("model")) {
                        String res = scanObjectForTroopMemberCard(val);
                        if (res != null) return res;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static View findViewByClassName(View root, String simpleName) {
        if (root == null) return null;
        if (root.getClass().getName().contains(simpleName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View hit = findViewByClassName(vg.getChildAt(i), simpleName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void updateCardConfig(Activity activity, View cardItem, String versionText, String uin) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> itemClz = cardItem.getClass();
            Class<?> xClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
            Class<?> xbdClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Class<?> xcgClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");

            Object left = xbdClz.getConstructor(CharSequence.class).newInstance("QQ 版本");
            Object right = xcgClz.getConstructor(CharSequence.class, boolean.class, boolean.class)
                    .newInstance(versionText, false, false);

            Constructor<?> xCtor = xClz.getConstructor(
                    cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                    cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
            );
            Object config = xCtor.newInstance(left, right);

            Method setConfigM = itemClz.getMethod("setConfig", xClz);
            setConfigM.invoke(cardItem, config);

            cardItem.setOnClickListener(v -> handleCardClick(activity, uin, versionText));
        } catch (Throwable ignored) {}
    }

    private static void handleCardClick(Activity activity, String uin, String versionText) {
        if (activity == null || activity.isFinishing()) return;
        if (QQVersionModule.UNKNOWN_STATUS.equals(versionText)) {
            ToastHelper.show(activity, "对方尚未产生消息推送，待对方在群聊发言后即可识别");
        } else {
            copyText(activity, "QQ号: " + uin + "  版本: " + versionText);
            ToastHelper.show(activity, "已复制版本信息: " + versionText);
        }
    }

    private static void copyText(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("qq_version", text));
            }
        } catch (Throwable ignored) {}
    }

    private static int dp2px(Context c, float dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}

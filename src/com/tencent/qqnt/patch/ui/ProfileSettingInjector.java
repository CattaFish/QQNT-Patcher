package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
     * 场景 1: ProfileCardMoreActivity (单聊好友设置 + 群成员右上角更多) 顶部独立卡片注入
     */
    public static void injectProfileCardMore(Object activityObj) {
        if (!(activityObj instanceof Activity)) return;

        Activity activity = (Activity) activityObj;
        try {
            // 1. 定位卡片容器 (通过 o0 备注项获取其直接父 LinearLayout)
            Field o0Field = activity.getClass().getDeclaredField("o0");
            o0Field.setAccessible(true);
            View o0View = (View) o0Field.get(activity);
            if (o0View == null) return;

            ViewGroup parent = (ViewGroup) o0View.getParent();
            if (parent == null) return;

            View existingCard = parent.findViewWithTag(TAG_VERSION_CARD);

            // 如果模块已关闭：若界面上已有卡片则立即移除，并直接返回
            if (!ConfigManager.isModuleEnabled("qq_version", false)) {
                if (existingCard != null) {
                    parent.removeView(existingCard);
                }
                return;
            }

            // 2. 获取 AllInOne a0 中的目标 UIN
            Field a0Field = activity.getClass().getDeclaredField("a0");
            a0Field.setAccessible(true);
            Object allInOne = a0Field.get(activity);
            if (allInOne == null) return;

            Field uinField = allInOne.getClass().getField("uin");
            String targetUin = (String) uinField.get(allInOne);
            if (targetUin == null || targetUin.isEmpty()) return;

            // 3. 反查版本号
            String versionText = QQVersionModule.getVersionByUin(targetUin);

            // 4. 防重检查: 如果已注入则直接更新文字，不重复建卡
            if (existingCard != null) {
                updateCardConfig(activity, existingCard, versionText, targetUin);
                return;
            }

            // 5. 动态构建原生 QUISingleLineListItem 独立卡片
            ClassLoader cl = activity.getClassLoader();
            Class<?> itemClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUISingleLineListItem");
            Class<?> bgTypeClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUIListItemBackgroundType");
            Class<?> styleClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUIListItemStyle");

            Constructor<?> ctor = itemClz.getConstructor(Context.class);
            View cardItem = (View) ctor.newInstance(activity);
            cardItem.setTag(TAG_VERSION_CARD);

            // AllRound + Card 组合，天然形成最顶部分离的独立卡片
            Object allRound = Enum.valueOf((Class<Enum>) bgTypeClz, "AllRound");
            Method setBgMethod = itemClz.getMethod("setBackgroundType", bgTypeClz);
            setBgMethod.invoke(cardItem, allRound);

            Object cardStyle = Enum.valueOf((Class<Enum>) styleClz, "Card");
            Method setStyleMethod = itemClz.getMethod("setStyle", styleClz);
            setStyleMethod.invoke(cardItem, cardStyle);

            // 无右侧箭头，纯文本展现
            updateCardConfig(activity, cardItem, versionText, targetUin);

            // 12dp 底部外间距，与下方备注项形成呼吸感分离
            int margin = dp2px(activity, 12f);
            if (parent instanceof LinearLayout) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = margin;
                cardItem.setLayoutParams(lp);
            }

            // 插入到最顶部 (第 0 位)
            parent.addView(cardItem, 0);
            PLog.i(TAG, "已在 ProfileCardMoreActivity 最顶部挂载独立版本卡片: " + versionText);

        } catch (Throwable t) {
            PLog.e(TAG, "injectProfileCardMore 异常", t);
        }
    }

    /**
     * 场景 2: MemberSettingFragment (群成员高级管理页) 顶部独立 Group 注入
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void injectMemberSettingGroups(Object fragment, Object groupsListObj) {
        if (fragment == null || !(groupsListObj instanceof ArrayList)) return;
        // 严格遵循默认关闭，仅当用户在 GUI 中开启后才注入
        if (!ConfigManager.isModuleEnabled("qq_version", false)) return;

        ArrayList groupsList = (ArrayList) groupsListObj;
        try {
            Field vmField = fragment.getClass().getDeclaredField("C");
            vmField.setAccessible(true);
            Object vm = vmField.get(fragment);
            if (vm == null) return;

            Method getUiModelMethod = vm.getClass().getMethod("M1");
            Object uiModel = getUiModelMethod.invoke(vm);
            if (uiModel == null) return;

            Method getUinMethod = uiModel.getClass().getMethod("n");
            String memberUin = (String) getUinMethod.invoke(uiModel);
            if (memberUin == null || memberUin.isEmpty()) return;

            String versionText = QQVersionModule.getVersionByUin(memberUin);

            ClassLoader cl = fragment.getClass().getClassLoader();
            Activity activity = null;
            try {
                activity = (Activity) fragment.getClass().getMethod("getActivity").invoke(fragment);
            } catch (Throwable ignored) {}

            final Activity finalAct = activity;
            // 构造无箭头单行卡片项
            Object textItem = NativeSettingHelper.createClickable(
                    cl, "QQ 版本", versionText, false, false,
                    v -> handleCardClick(finalAct, memberUin, versionText)
            );

            if (textItem != null) {
                // 封装为独立 Group 并插在第 0 位 (最上方)
                Object versionGroup = NativeSettingHelper.createGroup(
                        cl, "", "", Collections.singletonList(textItem)
                );
                if (versionGroup != null) {
                    groupsList.add(0, versionGroup);
                    PLog.i(TAG, "已在 MemberSettingFragment 最顶部挂载独立 Group: " + versionText);
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "injectMemberSettingGroups 异常", t);
        }
    }

    private static void updateCardConfig(Activity activity, View cardItem, String versionText, String uin) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> itemClz = cardItem.getClass();
            Class<?> xClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
            Class<?> xbdClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Class<?> xcgClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");

            Object left = xbdClz.getConstructor(CharSequence.class).newInstance("QQ 版本");
            // showArrow = false (无右箭头)
            Object right = xcgClz.getConstructor(CharSequence.class, boolean.class, boolean.class)
                    .newInstance(versionText, false, false);

            Constructor<?> xCtor = xClz.getConstructor(
                    cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                    cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
            );
            Object config = xCtor.newInstance(left, right);

            Method setConfigM = itemClz.getMethod("setConfig", xClz);
            setConfigM.invoke(cardItem, config);

            // 点击事件：未捕获时友好提示，已捕获时一键复制
            cardItem.setOnClickListener(v -> handleCardClick(activity, uin, versionText));
        } catch (Throwable ignored) {}
    }

    private static void handleCardClick(Activity activity, String uin, String versionText) {
        if (activity == null || activity.isFinishing()) return;
        if (QQVersionModule.UNKNOWN_STATUS.equals(versionText)) {
            ToastHelper.show(activity, "提示：对方尚未产生实时消息推送，待对方在群内或私聊发言一次后即可自动识别");
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

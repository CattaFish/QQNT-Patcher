package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.modules.chat.QQVersionModule;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;
import me.yxp.qfun.utils.ui.ThemeHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;

public class ProfileSettingInjector {

    private static final String TAG = "ProfileSettingInjector";
    private static final String TAG_VERSION_CARD = "zzz_qq_version_card_item";

    public static void injectProfileCardMore(Object activityObj) {
        if (!(activityObj instanceof Activity)) return;

        Activity activity = (Activity) activityObj;
        try {
            View sampleItem = findViewByClassName(activity.getWindow().getDecorView(), "QUISingleLineListItem");
            if (sampleItem == null) {
                sampleItem = findViewByClassName(activity.getWindow().getDecorView(), "FormSimpleItem");
            }
            if (sampleItem == null || !(sampleItem.getParent() instanceof ViewGroup)) return;

            ViewGroup parent = (ViewGroup) sampleItem.getParent();
            View existingCard = parent.findViewWithTag(TAG_VERSION_CARD);

            if (!ConfigManager.isModuleEnabled("qq_version", false)) {
                if (existingCard != null) parent.removeView(existingCard);
                return;
            }

            String targetUin = extractTargetUinFromActivity(activity);
            if (targetUin == null || targetUin.isEmpty()) return;

            long subId = QQVersionModule.getSubidByUin(targetUin);
            QQVersionModule.InferResult infer = (subId > 0) ? QQVersionModule.inferSubid(subId) : null;
            String versionText = (infer != null) ? infer.displaySummary : QQVersionModule.UNKNOWN_STATUS;

            int hMargin = dp2px(activity, 16f);
            int topMargin = dp2px(activity, 16f);
            int bottomMargin = dp2px(activity, 12f);

            if (existingCard != null) {
                updateCardConfig(activity, existingCard, versionText, targetUin, subId);
                applyCardMargins(existingCard, parent, hMargin, topMargin, bottomMargin);
                return;
            }

            ClassLoader cl = activity.getClassLoader();
            Class<?> itemClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUISingleLineListItem");
            Class<?> bgTypeClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUIListItemBackgroundType");
            Class<?> styleClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.QUIListItemStyle");

            Constructor<?> ctor = itemClz.getConstructor(Context.class);
            View cardItem = (View) ctor.newInstance(activity);
            cardItem.setTag(TAG_VERSION_CARD);

            Object allRound = Enum.valueOf((Class<Enum>) bgTypeClz, "AllRound");
            itemClz.getMethod("setBackgroundType", bgTypeClz).invoke(cardItem, allRound);

            Object cardStyle = Enum.valueOf((Class<Enum>) styleClz, "Card");
            itemClz.getMethod("setStyle", styleClz).invoke(cardItem, cardStyle);

            updateCardConfig(activity, cardItem, versionText, targetUin, subId);
            applyCardMargins(cardItem, parent, hMargin, topMargin, bottomMargin);

            parent.addView(cardItem, 0);
            PLog.i(TAG, "挂载独立版本卡片: " + versionText);

        } catch (Throwable t) {
            PLog.e(TAG, "injectProfileCardMore 异常", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void injectMemberSettingGroups(Object fragment, Object groupsListObj) {
        if (fragment == null || !(groupsListObj instanceof ArrayList)) return;
        if (!ConfigManager.isModuleEnabled("qq_version", false)) return;

        ArrayList groupsList = (ArrayList) groupsListObj;
        try {
            String memberUin = extractMemberUinFromFragment(fragment);
            if (memberUin == null || memberUin.isEmpty()) return;

            long subId = QQVersionModule.getSubidByUin(memberUin);
            QQVersionModule.InferResult infer = (subId > 0) ? QQVersionModule.inferSubid(subId) : null;
            String versionText = (infer != null) ? infer.displaySummary : QQVersionModule.UNKNOWN_STATUS;

            ClassLoader cl = fragment.getClass().getClassLoader();
            Activity activity = null;
            try {
                activity = (Activity) fragment.getClass().getMethod("getActivity").invoke(fragment);
            } catch (Throwable ignored) {}

            final Activity finalAct = activity;
            Object textItem = NativeSettingHelper.createClickable(
                    cl, "QQ 版本", versionText, true, false,
                    v -> handleCardClick(finalAct, memberUin, versionText, subId)
            );

            if (textItem != null) {
                Object versionGroup = NativeSettingHelper.createGroup(
                        cl, "", "", Collections.singletonList(textItem)
                );
                if (versionGroup != null) {
                    groupsList.add(0, versionGroup);
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "injectMemberSettingGroups 异常", t);
        }
    }

    private static void updateCardConfig(Activity activity, View cardItem, String versionText, String uin, long subId) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> itemClz = cardItem.getClass();
            Class<?> xClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
            Class<?> xbdClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Class<?> xcgClz = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");

            Object left = xbdClz.getConstructor(CharSequence.class).newInstance("QQ 版本");
            Object right = xcgClz.getConstructor(CharSequence.class, boolean.class, boolean.class)
                    .newInstance(versionText, true, false);

            Constructor<?> xCtor = xClz.getConstructor(
                    cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                    cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
            );
            Object config = xCtor.newInstance(left, right);
            itemClz.getMethod("setConfig", xClz).invoke(cardItem, config);

            cardItem.setOnClickListener(v -> handleCardClick(activity, uin, versionText, subId));
        } catch (Throwable ignored) {}
    }

    private static void handleCardClick(Activity activity, String uin, String versionText, long subId) {
        if (activity == null || activity.isFinishing()) return;

        if (QQVersionModule.UNKNOWN_STATUS.equals(versionText) || subId <= 0) {
            ToastHelper.show(activity, "对方尚未产生消息推送，待对方在群聊发言后即可识别");
            return;
        }

        QQVersionModule.InferResult infer = QQVersionModule.inferSubid(subId);
        showDetailDialog(activity, uin, subId, infer);
    }

    private static void showDetailDialog(Activity activity, String uin, long subId, QQVersionModule.InferResult infer) {
        boolean isNight = ThemeHelper.INSTANCE.isNightMode();

        int bgColor = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int textColor = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        int rowBgColor = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#F2F2F7");

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int pad = dp2px(activity, 20f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp2px(activity, 18f));
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText(infer != null && infer.isExact ? "客户端版本详细信息" : "客户端版本识别 (推测)");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 12f));
        root.addView(title);

        StringBuilder copySb = new StringBuilder();
        copySb.append("QQ号: ").append(uin).append("\n");
        copySb.append("协议 SubID: ").append(subId);

        // 如果不是精确匹配，挂载高亮警告条
        if (infer != null && !infer.isExact) {
            TextView warningBox = new TextView(activity);
            warningBox.setTextSize(12);
            warningBox.setTextColor(isNight ? Color.parseColor("#FFD60A") : Color.parseColor("#B25E00"));
            warningBox.setLineSpacing(dp2px(activity, 2f), 1f);
            warningBox.setText("⚠️ 未精确匹配警告：\n" + infer.warningNote);
            warningBox.setPadding(dp2px(activity, 10f), dp2px(activity, 8f), dp2px(activity, 10f), dp2px(activity, 8f));

            GradientDrawable warnBg = new GradientDrawable();
            warnBg.setColor(isNight ? Color.parseColor("#332B00") : Color.parseColor("#FFF9E6"));
            warnBg.setCornerRadius(dp2px(activity, 8f));
            warnBg.setStroke(dp2px(activity, 1f), isNight ? Color.parseColor("#665700") : Color.parseColor("#FFE58F"));
            warningBox.setBackground(warnBg);

            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wLp.bottomMargin = dp2px(activity, 12f);
            root.addView(warningBox, wLp);

            copySb.append(" (未精确收录)\n");
        } else {
            copySb.append("\n");
        }

        LinearLayout infoBox = new LinearLayout(activity);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(dp2px(activity, 12f), dp2px(activity, 8f), dp2px(activity, 12f), dp2px(activity, 8f));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(rowBgColor);
        boxBg.setCornerRadius(dp2px(activity, 12f));
        infoBox.setBackground(boxBg);

        addInfoRow(activity, infoBox, "目标 QQ 号", uin, textColor, subTextColor);
        addInfoRow(activity, infoBox, "协议 SubID", String.valueOf(subId), textColor, subTextColor);

        if (infer != null && infer.isExact && infer.exactDetail != null) {
            QQVersionModule.SubidDetail detail = infer.exactDetail;
            if (detail.platform != null && !detail.platform.isEmpty()) {
                addInfoRow(activity, infoBox, "操作系统平台", detail.platform, textColor, subTextColor);
                copySb.append("平台: ").append(detail.platform).append("\n");
            }
            if (detail.arch != null && !detail.arch.isEmpty()) {
                addInfoRow(activity, infoBox, "系统运行架构", detail.arch, textColor, subTextColor);
                copySb.append("架构: ").append(detail.arch).append("\n");
            }
            if (detail.packages != null && !detail.packages.isEmpty()) {
                String pkgs = String.join(" / ", detail.packages);
                addInfoRow(activity, infoBox, "分发支持包", pkgs, textColor, subTextColor);
                copySb.append("分发包: ").append(pkgs).append("\n");
            }
            if (detail.version != null && !detail.version.isEmpty()) {
                addInfoRow(activity, infoBox, "精确版本号", detail.version, textColor, subTextColor);
                copySb.append("版本: ").append(detail.version).append("\n");
            }
            if (detail.date != null && !detail.date.isEmpty()) {
                addInfoRow(activity, infoBox, "发布构建日期", detail.date, textColor, subTextColor);
                copySb.append("日期: ").append(detail.date).append("\n");
            }
        } else if (infer != null && !infer.isExact) {
            if (!infer.suspectedPlatform.isEmpty()) {
                addInfoRow(activity, infoBox, "推测平台", infer.suspectedPlatform + " (可能不准)", textColor, subTextColor);
                copySb.append("推测平台: ").append(infer.suspectedPlatform).append("\n");
            }
            if (!infer.suspectedArch.isEmpty()) {
                addInfoRow(activity, infoBox, "推测架构", infer.suspectedArch + " (可能不准)", textColor, subTextColor);
                copySb.append("推测架构: ").append(infer.suspectedArch).append("\n");
            }
            if (!infer.suspectedVersion.isEmpty()) {
                addInfoRow(activity, infoBox, "可疑版本", infer.suspectedVersion + " (可疑，供参考)", textColor, subTextColor);
                copySb.append("可疑版本: ").append(infer.suspectedVersion).append(" (可疑)\n");
            }
            if (infer.floorDetail != null) {
                addInfoRow(activity, infoBox, "前一已知版本", infer.floorSubid + " (" + infer.floorDetail.summary + ")", textColor, subTextColor);
            }
            if (infer.ceilDetail != null) {
                addInfoRow(activity, infoBox, "后一已知版本", infer.ceilSubid + " (" + infer.ceilDetail.summary + ")", textColor, subTextColor);
            }
        }

        root.addView(infoBox);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 16f), 0, 0);

        Button copyBtn = new Button(activity);
        copyBtn.setText("复制完整信息");
        copyBtn.setTextSize(14);
        copyBtn.setTextColor(Color.WHITE);
        copyBtn.setAllCaps(false);
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(Color.parseColor("#007AFF"));
        copyBg.setCornerRadius(dp2px(activity, 10f));
        copyBtn.setBackground(copyBg);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        copyLp.rightMargin = dp2px(activity, 8f);
        copyBtn.setOnClickListener(v -> {
            copyText(activity, copySb.toString().trim());
            ToastHelper.show(activity, "已复制全部信息");
            dialog.dismiss();
        });
        btnRow.addView(copyBtn, copyLp);

        Button okBtn = new Button(activity);
        okBtn.setText("关闭");
        okBtn.setTextSize(14);
        okBtn.setTextColor(subTextColor);
        okBtn.setAllCaps(false);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(rowBgColor);
        okBg.setCornerRadius(dp2px(activity, 10f));
        okBtn.setBackground(okBg);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        okBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(okBtn, okLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void addInfoRow(Context context, LinearLayout parent, String label, String value, int labelColor, int valColor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp2px(context, 4f), 0, dp2px(context, 4f));

        TextView tvLabel = new TextView(context);
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(valColor);
        row.addView(tvLabel, new LinearLayout.LayoutParams(dp2px(context, 90f), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvVal = new TextView(context);
        tvVal.setText(value);
        tvVal.setTextSize(13);
        tvVal.getPaint().setFakeBoldText(true);
        tvVal.setTextColor(labelColor);
        row.addView(tvVal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

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
            Bundle args = (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment);
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
                    if (val instanceof String && ((String) val).matches("[1-9]\\d{4,12}")) return (String) val;
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
                if (val != null && val.getClass().getName().contains("TroopMemberCard")) {
                    Field fUin = val.getClass().getField("memberUin");
                    return (String) fUin.get(val);
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

    private static void copyText(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("qq_version_detail", text));
            }
        } catch (Throwable ignored) {}
    }

    private static int dp2px(Context c, float dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}

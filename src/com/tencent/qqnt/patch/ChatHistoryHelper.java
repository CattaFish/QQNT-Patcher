package com.tencent.qqnt.patch;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.qqnt.patch.plugin.MsgSender;
import me.yxp.qfun.utils.ui.ThemeHelper;

public class ChatHistoryHelper {

    private static final String TARGET_ACTIVITY = "com.tencent.mobileqq.activity.history.NTChatHistoryActivity";

    // =========================================================================
    // 1. 底层 Activity 启动调度
    // =========================================================================

    /**
     * 打开私聊聊天记录 (chatType = 1)
     */
    public static void openFriendChatHistory(Context context, String input) {
        if (context == null || input == null) return;
        String text = input.trim();
        if (text.isEmpty()) {
            ToastHelper.show(context, "请输入有效的 QQ 号或 UID");
            return;
        }

        String peerId = null;
        String sessionName = text;

        if (text.startsWith("u_")) {
            // 输入的是 UID
            peerId = text;
        } else {
            // 输入的是纯数字 QQ 号
            try {
                long uin = Long.parseLong(text);
                if (uin < 10000L) {
                    ToastHelper.show(context, "请输入有效的 QQ 号");
                    return;
                }
                String uid = MsgSender.getUidFromUin(text);
                if (uid != null && uid.startsWith("u_")) {
                    peerId = uid;
                } else {
                    ToastHelper.show(context, "本地未检索到该 QQ 对应 UID，若知晓请直接输入 UID (u_...)");
                    return;
                }
            } catch (NumberFormatException e) {
                ToastHelper.show(context, "格式错误，请输入纯数字 QQ 或以 u_ 开头的 UID");
                return;
            }
        }

        launchChatHistory(context, peerId, 1, sessionName);
    }

    /**
     * 打开群聊聊天记录 (chatType = 2, peerId 直接为群号)
     */
    public static void openGroupChatHistory(Context context, String input) {
        if (context == null || input == null) return;
        String text = input.trim();
        if (text.isEmpty()) {
            ToastHelper.show(context, "请输入群号");
            return;
        }

        try {
            long gUin = Long.parseLong(text);
            if (gUin < 10000L) {
                ToastHelper.show(context, "请输入有效的群号");
                return;
            }
        } catch (NumberFormatException e) {
            ToastHelper.show(context, "群号必须为纯数字");
            return;
        }

        launchChatHistory(context, text, 2, text);
    }

    private static void launchChatHistory(Context context, String peerId, int chatType, String sessionName) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), TARGET_ACTIVITY);
            intent.putExtra("nt_chat_history_peerId", peerId);
            intent.putExtra("nt_chat_history_chatType", chatType);
            if (sessionName != null && !sessionName.isEmpty()) {
                intent.putExtra("nt_chat_history_session_name", sessionName);
            }
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            PLog.e("ChatHistory", "启动聊天记录界面异常", t);
            ToastHelper.show(context, "打开聊天记录失败: " + t.getMessage());
        }
    }

    // =========================================================================
    // 2. 原生 UI 弹窗 (自适应暗黑模式，零混淆依赖)
    // =========================================================================

    public static void showFriendHistoryDialog(Activity activity) {
        showCustomInputDialog(
                activity,
                "打开好友聊天记录",
                "[提示] 支持输入好友 QQ 号或 UID (u_...)\n仅支持读取本地已有的聊天记录",
                "请输入好友 QQ 号 或 UID",
                false,
                input -> openFriendChatHistory(activity, input)
        );
    }

    public static void showGroupHistoryDialog(Activity activity) {
        showCustomInputDialog(
                activity,
                "打开群聊聊天记录",
                "[提示] 支持输入目标群号\n可查看本地保留的历史记录（含已退出的群）",
                "请输入目标群号",
                true,
                input -> openGroupChatHistory(activity, input)
        );
    }

    private interface OnInputSubmitListener {
        void onSubmit(String text);
    }

    private static void showCustomInputDialog(Activity activity, String titleText, String tipText,
                                              String hintText, boolean isNumericOnly, OnInputSubmitListener listener) {
        if (activity == null || activity.isFinishing()) return;

        boolean isNight = ThemeHelper.INSTANCE.isNightMode();
        int bgColor = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int textColor = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        int inputBgColor = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#F2F2F7");

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

        // 标题
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 10f));
        root.addView(title);

        // 提示说明
        TextView tip = new TextView(activity);
        tip.setText(tipText);
        tip.setTextSize(12);
        tip.setTextColor(subTextColor);
        tip.setLineSpacing(dp2px(activity, 2f), 1f);
        tip.setPadding(dp2px(activity, 4f), 0, dp2px(activity, 4f), dp2px(activity, 12f));
        root.addView(tip);

        // 输入框
        EditText et = new EditText(activity);
        et.setHint(hintText);
        et.setHintTextColor(subTextColor);
        et.setTextColor(textColor);
        et.setTextSize(14);
        et.setSingleLine(true);
        if (isNumericOnly) {
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        et.setPadding(dp2px(activity, 12f), 0, dp2px(activity, 12f), 0);

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(inputBgColor);
        inputBg.setCornerRadius(dp2px(activity, 10f));
        et.setBackground(inputBg);

        LinearLayout.LayoutParams lpInput = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f));
        lpInput.bottomMargin = dp2px(activity, 16f);
        root.addView(et, lpInput);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(14);
        cancelBtn.setTextColor(subTextColor);
        cancelBtn.setAllCaps(false);

        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(inputBgColor);
        cancelBg.setCornerRadius(dp2px(activity, 10f));
        cancelBtn.setBackground(cancelBg);

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        cancelLp.rightMargin = dp2px(activity, 8f);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn, cancelLp);

        Button confirmBtn = new Button(activity);
        confirmBtn.setText("查看记录");
        confirmBtn.setTextSize(14);
        confirmBtn.setTextColor(Color.WHITE);
        confirmBtn.setAllCaps(false);

        GradientDrawable confirmBg = new GradientDrawable();
        confirmBg.setColor(Color.parseColor("#007AFF"));
        confirmBg.setCornerRadius(dp2px(activity, 10f));
        confirmBtn.setBackground(confirmBg);

        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        confirmBtn.setOnClickListener(v -> {
            String txt = et.getText() != null ? et.getText().toString().trim() : "";
            if (txt.isEmpty()) {
                ToastHelper.show(activity, "输入内容不能为空");
                return;
            }
            dialog.dismiss();
            if (listener != null) {
                listener.onSubmit(txt);
            }
        });
        btnRow.addView(confirmBtn, confirmLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
package com.tencent.qqnt.patch.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;

public class ChatHistoryHelper {

    private static final String TARGET_ACTIVITY = "com.tencent.mobileqq.activity.history.NTChatHistoryActivity";

    public static void openFriendChatHistory(Context context, String input) {
        if (context == null || input == null) return;
        String text = input.trim();
        if (text.isEmpty()) {
            ToastHelper.show(context, "请输入有效的 QQ 号或 UID");
            return;
        }

        String peerId = text;
        String sessionName = text;

        if (!text.startsWith("u_")) {
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
                    PLog.w("ChatHistory", "本地关系链未检索到 UID，直接使用 QQ 号尝试拉起");
                }
            } catch (NumberFormatException e) {
                ToastHelper.show(context, "格式错误，请输入纯数字 QQ 或以 u_ 开头的 UID");
                return;
            }
        }

        launchChatHistory(context, peerId, 1, sessionName);
    }

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

    public static void showFriendHistoryDialog(Activity activity) {
        SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "打开好友聊天记录");
        builder.addTip("[提示] 支持输入好友 QQ 号或 UID (u_...)\n仅支持读取本地已有的聊天记录", null)
                .addInput("请输入好友 QQ 号 或 UID", "", true, false, null)
                .setPositiveButton("查看记录", dialog -> {
                    String input = builder.getInputValue(0);
                    if (input.isEmpty()) {
                        ToastHelper.show(activity, "输入不能为空");
                        return;
                    }
                    dialog.dismiss();
                    openFriendChatHistory(activity, input);
                })
                .show();
    }

    public static void showGroupHistoryDialog(Activity activity) {
        SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "打开群聊聊天记录");
        builder.addTip("[提示] 支持输入目标群号\n可查看本地保留的历史记录（含已退出的群）", null)
                .addInput("请输入目标群号", "", true, true, null)
                .setPositiveButton("查看记录", dialog -> {
                    String input = builder.getInputValue(0);
                    if (input.isEmpty()) {
                        ToastHelper.show(activity, "群号不能为空");
                        return;
                    }
                    dialog.dismiss();
                    openGroupChatHistory(activity, input);
                })
                .show();
    }
}
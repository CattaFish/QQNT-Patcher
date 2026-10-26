package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.mobileqq.aio.msg.AIOMsgItem;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;
import me.yxp.qfun.utils.ui.ThemeHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class DoubleTapRepeatModule implements IPatchModule {

    private static final String TAG = "RepeatMsg";

    public static final String KEY_REPEAT_MODE = "zzz_repeat_mode";
    public static final int MODE_SWIPE = 0;    // 方案一：左滑复读 (默认)
    public static final int MODE_PLUS_ONE = 1; // 方案二：强制显示 +1 图标

    private static volatile long sLastRepeatTime = 0L;

    @Override public String getId() { return "double_tap_repeat"; }
    @Override public String getName() { return "消息复读"; }
    @Override public String getCategory() { return CATEGORY_CHAT; }
    @Override public String getSubName() {
        if (!isEnabled()) {
            return "支持左滑消息或气泡旁 +1 图标复读";
        }
        int mode = getRepeatMode();
        return (mode == MODE_SWIPE ? "方案一：左滑消息复读" : "方案二：气泡旁显示 +1 图标");
    }
    @Override public boolean defaultEnabled() { return false; }
    @Override public boolean hasConfig() { return true; }

    public static int getRepeatMode() {
        String val = ConfigManager.getString(KEY_REPEAT_MODE, String.valueOf(MODE_SWIPE));
        try {
            return Integer.parseInt(val.trim());
        } catch (Throwable ignored) {
            return MODE_SWIPE;
        }
    }

    public static void setRepeatMode(int mode) {
        ConfigManager.setString(KEY_REPEAT_MODE, String.valueOf(mode));
    }

    public static boolean isSwipeModeActive() {
        return ConfigManager.isModuleEnabled("double_tap_repeat", false) && (getRepeatMode() == MODE_SWIPE);
    }

    public static boolean isPlusOneModeActive() {
        return ConfigManager.isModuleEnabled("double_tap_repeat", false) && (getRepeatMode() == MODE_PLUS_ONE);
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 消息复读配置", "配置", true, false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    /**
     * 动态读取官方左滑阈值 (完全消除对方法名 "c" 的硬编码)
     */
    private static int getOfficialSwipeThreshold() {
        try {
            Class<?> constClz = Class.forName("com.tencent.mobileqq.aio.msglist.holder.component.leftswipearea.LeftSwipeConstants");
            Object inst = null;
            for (Field f : constClz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == constClz) {
                    f.setAccessible(true);
                    inst = f.get(null);
                    break;
                }
            }

            if (inst != null) {
                for (Method m : constClz.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class) {
                        m.setAccessible(true);
                        return (Integer) m.invoke(inst);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 安全兜底
        Context ctx = AppContext.get();
        if (ctx != null) {
            float density = ctx.getResources().getDisplayMetrics().density;
            return (int) (40f * density + 0.5f);
        }
        return 80;
    }

    /**
     * 【方案 1：左滑松手触发入口】
     */
    public static boolean onLeftSwipeTrigger(Object componentObj, int scrollX) {
        if (!isSwipeModeActive() || componentObj == null) {
            return false;
        }

        int officialThreshold = getOfficialSwipeThreshold();
        if (scrollX <= officialThreshold) {
            return false;
        }

        try {
            // 按类型自省查找 AIOMsgItem (不依赖混淆字段名 "i")
            Object msgItem = getFieldByTypeRecursive(componentObj, "AIOMsgItem");
            if (msgItem != null) {
                performRepeat(msgItem);
                return true; // 静默发包
            }
        } catch (Throwable t) {
            PLog.e(TAG, "左滑复读异常", t);
        }

        return true;
    }

    /**
     * 【方案 2 核心：官方原生监听器点击拦截 (完全消除 "d", "h", "X1" 等混淆名)】
     */
    public static boolean onFollowIconClick(Object listenerObj) {
        if (!isPlusOneModeActive() || listenerObj == null) {
            return false;
        }

        try {
            // 1. 按类型查找宿主组件 AIOMsgFollowComponent (不依赖混淆字段名 "d")
            Object component = getFieldByTypeRecursive(listenerObj, "AIOMsgFollowComponent");

            if (component != null) {
                // 2. 按类型查找消息项 AIOMsgItem (不依赖混淆字段名 "h")
                Object msgItem = getFieldByTypeRecursive(component, "AIOMsgItem");
                if (msgItem == null) {
                    // 兜底：查找返回类型为 AIOMsgItem 的无参方法或单参静态方法 (不依赖合成方法 "X1")
                    msgItem = invokeMethodReturningType(component, "AIOMsgItem");
                }

                if (msgItem != null) {
                    performRepeat(msgItem);
                    return true; // 拦截成功，阻断官方对图片视频无效的默认意图
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "onFollowIconClick 异常", t);
        }

        return false;
    }

    /**
     * 【复读发包核心实现（支持纯文本直发，图片/视频转发通道）】
     */
    public static boolean performRepeat(Object msgItemOrRecord) {
        if (msgItemOrRecord == null) return false;

        long now = SystemClock.uptimeMillis();
        if (now - sLastRepeatTime < 450L) {
            return true;
        }
        sLastRepeatTime = now;

        MsgRecord record = extractMsgRecord(msgItemOrRecord);
        if (record == null) return false;

        try {
            String targetPeer = (record.peerUid != null && !record.peerUid.isEmpty())
                    ? record.peerUid
                    : String.valueOf(record.peerUin);
            Contact contact = new Contact(record.chatType, targetPeer, "");

            // 富媒体（图片/视频/大表情/文件）走 forwardMsg 转发通道
            if (isNeedForward(record)) {
                boolean sent = forwardSend(contact, record);
                if (sent) return true;
            }

            // 纯文本走 directSend 直发
            return directSend(contact, record);
        } catch (Throwable t) {
            PLog.e(TAG, "performRepeat 异常", t);
        }
        return false;
    }

    private static MsgRecord extractMsgRecord(Object obj) {
        if (obj == null) return null;
        if (obj instanceof MsgRecord) return (MsgRecord) obj;

        if (obj instanceof AIOMsgItem) {
            AIOMsgItem item = (AIOMsgItem) obj;
            if (item.getMsgRecord() != null) return item.getMsgRecord();
            if (item.msgRecord != null) return item.msgRecord;
        }

        try {
            Method m = obj.getClass().getMethod("getMsgRecord");
            Object res = m.invoke(obj);
            if (res instanceof MsgRecord) return (MsgRecord) res;
        } catch (Throwable ignored) {}

        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                if (MsgRecord.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object res = f.get(obj);
                        if (res instanceof MsgRecord) return (MsgRecord) res;
                    } catch (Throwable ignored) {}
                }
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    private static boolean isNeedForward(MsgRecord record) {
        int type = record.msgType;
        if (type == 3 || type == 7) return true;
        if (type == 2) {
            if (hasRichMediaElement(record)) return true;
            return !hasAtElement(record);
        }
        return false;
    }

    private static boolean hasRichMediaElement(MsgRecord record) {
        if (record.elements == null) return false;
        for (MsgElement elem : record.elements) {
            if (elem == null) continue;
            if (elem.picElement != null || elem.marketFaceElement != null || elem.fileElement != null || elem.elementType == 5) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAtElement(MsgRecord record) {
        if (record.elements == null) return false;
        for (MsgElement elem : record.elements) {
            if (elem != null && elem.textElement != null && elem.textElement.atType == 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean forwardSend(Contact contact, MsgRecord record) {
        try {
            IKernelMsgService msgService = getNativeKernelMsgService();
            if (msgService == null) return false;

            ArrayList<Long> msgIds = new ArrayList<>();
            msgIds.add(record.msgId);

            ArrayList<Contact> dstContacts = new ArrayList<>();
            dstContacts.add(contact);

            Object msgAttrs = getFieldByTypeRecursive(record, "HashMap");

            for (Method m : msgService.getClass().getMethods()) {
                if ("forwardMsg".equals(m.getName()) && m.getParameterTypes().length == 5) {
                    m.setAccessible(true);
                    m.invoke(msgService, msgIds, contact, dstContacts, msgAttrs, null);
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "forwardSend 异常", t);
        }
        return false;
    }

    private static boolean directSend(Contact contact, MsgRecord record) {
        try {
            IKernelMsgService msgService = getNativeKernelMsgService();
            if (msgService != null) {
                long msgId = 0L;
                try {
                    Method genMethod = msgService.getClass().getMethod("generateMsgUniqueId", int.class);
                    Object res = genMethod.invoke(msgService, contact.getChatType());
                    if (res instanceof Long) msgId = (Long) res;
                } catch (Throwable ignored) {}

                Object msgAttrs = getFieldByTypeRecursive(record, "HashMap");

                for (Method m : msgService.getClass().getMethods()) {
                    if ("sendMsg".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                        m.setAccessible(true);
                        if (m.getParameterTypes().length == 5) {
                            m.invoke(msgService, msgId, contact, record.elements, msgAttrs, null);
                        } else {
                            m.invoke(msgService, msgId, contact, record.elements, msgAttrs);
                        }
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        MsgSender.sendRawElements(contact, record.elements);
        return true;
    }

    private static IKernelMsgService getNativeKernelMsgService() {
        IKernelMsgService service = MsgSender.getMsgService();
        if (service != null) return service;
        try {
            Class<?> fClz = Class.forName("com.tencent.qqnt.msg.f");
            Object session = fClz.getMethod("j").invoke(null);
            if (session instanceof IQQNTWrapperSession) {
                return ((IQQNTWrapperSession) session).getMsgService();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 【设置弹窗】
     */
    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;

        boolean isNight = ThemeHelper.INSTANCE.isNightMode();

        int bgColor = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int textColor = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        int cardBgColor = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#F2F2F7");

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
        title.setText("消息复读设置");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 14f));
        root.addView(title);

        final int[] selectedMode = new int[]{ getRepeatMode() };

        // 方案 1 卡片
        LinearLayout option1 = createOptionCard(
                activity, isNight, textColor, subTextColor, cardBgColor,
                "方案一：左滑消息复读",
                "向左滑动任意消息复读发送，替代消息回复",
                selectedMode[0] == MODE_SWIPE
        );
        root.addView(option1);

        // 方案 2 卡片
        LinearLayout option2 = createOptionCard(
                activity, isNight, textColor, subTextColor, cardBgColor,
                "方案二：气泡旁显示 +1 图标",
                "在每条消息气泡旁边显示官方 +1 复读图标，支持文本/图片/视频一键复读",
                selectedMode[0] == MODE_PLUS_ONE
        );
        root.addView(option2);

        Runnable refreshSelection = () -> {
            updateOptionCard(option1, selectedMode[0] == MODE_SWIPE, isNight, cardBgColor);
            updateOptionCard(option2, selectedMode[0] == MODE_PLUS_ONE, isNight, cardBgColor);
        };

        option1.setOnClickListener(v -> {
            selectedMode[0] = MODE_SWIPE;
            refreshSelection.run();
        });

        option2.setOnClickListener(v -> {
            selectedMode[0] = MODE_PLUS_ONE;
            refreshSelection.run();
        });

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 16f), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(14);
        cancelBtn.setTextColor(subTextColor);
        cancelBtn.setAllCaps(false);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(cardBgColor);
        cancelBg.setCornerRadius(dp2px(activity, 10f));
        cancelBtn.setBackground(cancelBg);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        cancelLp.rightMargin = dp2px(activity, 8f);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn, cancelLp);

        Button saveBtn = new Button(activity);
        saveBtn.setText("保存并应用");
        saveBtn.setTextSize(14);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setAllCaps(false);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(Color.parseColor("#007AFF"));
        saveBg.setCornerRadius(dp2px(activity, 10f));
        saveBtn.setBackground(saveBg);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        saveBtn.setOnClickListener(v -> {
            setRepeatMode(selectedMode[0]);
            setEnabled(true);
            dialog.dismiss();
            ToastHelper.show(activity, "已保存消息复读配置");
            if (onSaved != null) onSaved.run();
        });
        btnRow.addView(saveBtn, saveLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static LinearLayout createOptionCard(Context context, boolean isNight, int textColor, int subTextColor,
                                                  int cardBgColor, String title, String desc, boolean isSelected) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp2px(context, 14f), dp2px(context, 12f), dp2px(context, 14f), dp2px(context, 12f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(context, 10f);
        card.setLayoutParams(lp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setTextColor(textColor);
        card.addView(tvTitle);

        TextView tvDesc = new TextView(context);
        tvDesc.setText(desc);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(subTextColor);
        tvDesc.setLineSpacing(dp2px(context, 2f), 1f);
        tvDesc.setPadding(0, dp2px(context, 4f), 0, 0);
        card.addView(tvDesc);

        updateOptionCard(card, isSelected, isNight, cardBgColor);
        return card;
    }

    private static void updateOptionCard(LinearLayout card, boolean isSelected, boolean isNight, int defaultBg) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp2px(card.getContext(), 12f));
        if (isSelected) {
            gd.setColor(isNight ? Color.parseColor("#1A3B66") : Color.parseColor("#EBF3FF"));
            gd.setStroke(dp2px(card.getContext(), 1.5f), Color.parseColor("#007AFF"));
        } else {
            gd.setColor(defaultBg);
            gd.setStroke(0, Color.TRANSPARENT);
        }
        card.setBackground(gd);
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null) return (int) (dp * 2f + 0.5f);
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 按 Class 类型自省查找字段 (完全消灭混淆字段名硬编码)
     */
    private static Object getFieldByTypeRecursive(Object obj, String simpleTypeName) {
        if (obj == null) return null;
        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                if (f.getType().getName().contains(simpleTypeName)) {
                    try {
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (Throwable ignored) {}
                }
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    /**
     * 按返回类型自省调用无参/单参方法 (完全消灭混淆方法名硬编码)
     */
    private static Object invokeMethodReturningType(Object obj, String simpleReturnTypeName) {
        if (obj == null) return null;
        Class<?> clz = obj.getClass();
        for (Method m : clz.getDeclaredMethods()) {
            if (m.getReturnType().getName().contains(simpleReturnTypeName)) {
                try {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 0) {
                        return m.invoke(obj);
                    } else if (m.getParameterTypes().length == 1 && Modifier.isStatic(m.getModifiers())
                            && m.getParameterTypes()[0] == clz) {
                        return m.invoke(null, obj);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public interface IPatchModule {
    String CATEGORY_CHAT = "chat";
    String CATEGORY_PURIFY = "purify";
    String CATEGORY_ADVANCED = "advanced";

    String getId();
    String getName();
    default String getCategory() { return CATEGORY_CHAT; }
    default String getSubName() { return ""; }
    default boolean defaultEnabled() { return false; }
    default boolean isEnabled() { return ConfigManager.isModuleEnabled(getId(), defaultEnabled()); }
    default void setEnabled(boolean enabled) { ConfigManager.setModuleEnabled(getId(), enabled); }
    default boolean showInSettings() { return true; }
    default boolean hasConfig() { return false; }
    default void onConfigClick(Activity activity, Runnable onSaved) {}
    default List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        if (hasConfig()) {
            List<Object> list = new ArrayList<>();
            list.add(NativeSettingHelper.createClickable(
                    cl, "  ↳ " + getName() + "配置", "配置", true, false,
                    v -> onConfigClick(activity, onRefresh)
            ));
            return list;
        }
        return Collections.emptyList();
    }

    default void onInit(Context context) {}
    default byte[] onMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) { return buf; }
    default void onSendMsg(ArrayList<MsgElement> elements) {}
    default void onRecvMsg(List<MsgRecord> msgList) {}
    default void onAIOMsgItem(MsgRecord record) {}
    default void onAIOShow(Object delegate) {}
    default void onAIOHide() {}
}

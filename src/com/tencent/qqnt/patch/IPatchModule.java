package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;

import java.util.ArrayList;
import java.util.List;

public interface IPatchModule {
    /** 模块唯一标识 (用于持久化标记) */
    String getId();

    /** 模块主标题 (显示在左侧第一行) */
    String getName();

    /** 模块副标题/描述 (显示在左侧第二行，为空则单行显示) */
    default String getSubName() { return ""; }

    /** 默认开关状态 (全部统一默认关闭) */
    default boolean defaultEnabled() { return false; }

    /** 是否启用 */
    default boolean isEnabled() {
        return ConfigManager.isModuleEnabled(getId(), defaultEnabled());
    }

    /** 设置开关状态 */
    default void setEnabled(boolean enabled) {
        ConfigManager.setModuleEnabled(getId(), enabled);
    }

    /** 是否在设置页面自动生成开关 */
    default boolean showInSettings() { return true; }

    /** 是否支持独立配置弹窗 */
    default boolean hasConfig() { return false; }

    /** 点击配置项回调 */
    default void onConfigClick(Activity activity, Runnable onSaved) {}

    // === 事件生命周期分发 ===
    default void onInit(Context context) {}
    default byte[] onMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) { return buf; }
    default void onSendMsg(ArrayList<MsgElement> elements) {}
    default void onRecvMsg(List<MsgRecord> msgList) {}
    default void onAIOMsgItem(MsgRecord record) {}
    default void onAIOShow(Object delegate) {}
    default void onAIOHide() {}
}
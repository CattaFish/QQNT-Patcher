package com.tencent.qqnt.patch;

import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModuleManager {
    private static final String TAG = "ModuleManager";
    private static final List<IPatchModule> sModules = new CopyOnWriteArrayList<>();
    private static volatile boolean sInitialized = false;

    static {
        register(new com.tencent.qqnt.patch.modules.AntiRevokeModule());
        register(new com.tencent.qqnt.patch.modules.FlashPicModule());
        register(new com.tencent.qqnt.patch.modules.MeowModule());
        register(new com.tencent.qqnt.patch.modules.FloatingBallModule());
        register(new com.tencent.qqnt.patch.modules.TabletModeModule());
        register(new com.tencent.qqnt.patch.modules.AtAllNotifyBlockModule());
        register(new com.tencent.qqnt.patch.modules.AutoRemarkApkModule());
        register(new com.tencent.qqnt.patch.modules.ShowDownloadTimesModule());
        register(new com.tencent.qqnt.patch.modules.ModifyPicSummaryModule());
        register(new com.tencent.qqnt.patch.modules.DisableSplitScreenLimitModule()); // ★ 新增注册
    }

    public static void register(IPatchModule module) {
        if (module != null && !sModules.contains(module)) {
            sModules.add(module);
            PLog.d(TAG, "注册模块 -> [" + module.getName() + "]");
        }
    }

    public static List<IPatchModule> getModules() {
        return Collections.unmodifiableList(sModules);
    }

    public static void initAll(Context context) {
        if (sInitialized || context == null) return;
        sInitialized = true;
        PLog.i(TAG, "挂载核心模块，共 " + sModules.size() + " 个模块就绪");
        for (IPatchModule m : sModules) {
            try {
                m.onInit(context);
                PLog.d(TAG, "  -> 模块 [" + m.getName() + "]: " + (m.isEnabled() ? "已开启" : "已关闭"));
            } catch (Throwable t) {
                PLog.e(TAG, "模块 [" + m.getId() + "] 初始化异常", t);
            }
        }
    }

    public static byte[] dispatchMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) {
        byte[] current = buf;
        for (IPatchModule m : sModules) {
            if (m.isEnabled()) {
                try {
                    current = m.onMsfPush(session, cmd, current);
                    if (current == null) return null;
                } catch (Throwable t) {
                    PLog.e(TAG, "[" + m.getId() + "] onMsfPush 异常", t);
                }
            }
        }
        return current;
    }

    public static void dispatchSendMsg(ArrayList<MsgElement> elements) {
        for (IPatchModule m : sModules) {
            if (m.isEnabled()) {
                try {
                    m.onSendMsg(elements);
                } catch (Throwable t) {
                    PLog.e(TAG, "[" + m.getId() + "] onSendMsg 异常", t);
                }
            }
        }
    }

    public static void dispatchRecvMsg(List<MsgRecord> msgList) {
        for (IPatchModule m : sModules) {
            if (m.isEnabled()) {
                try {
                    m.onRecvMsg(msgList);
                } catch (Throwable t) {
                    PLog.e(TAG, "[" + m.getId() + "] onRecvMsg 异常", t);
                }
            }
        }
    }

    public static void dispatchAIOMsgItem(MsgRecord record) {
        for (IPatchModule m : sModules) {
            if (m.isEnabled()) {
                try {
                    m.onAIOMsgItem(record);
                } catch (Throwable t) {
                    PLog.e(TAG, "[" + m.getId() + "] onAIOMsgItem 异常", t);
                }
            }
        }
    }

    public static void dispatchAIOShow(Object delegate) {
        PLog.d("AIO", "进入聊天会话 (AIO)");
        for (IPatchModule m : sModules) {
            if (m.isEnabled()) {
                try {
                    m.onAIOShow(delegate);
                } catch (Throwable t) {
                    PLog.e(TAG, "[" + m.getId() + "] onAIOShow 异常", t);
                }
            }
        }
    }

    public static void dispatchAIOHide() {
        PLog.d("AIO", "退出聊天会话 (AIO)");
        for (IPatchModule m : sModules) {
            if (m.isEnabled()) {
                try {
                    m.onAIOHide();
                } catch (Throwable t) {
                    PLog.e(TAG, "[" + m.getId() + "] onAIOHide 异常", t);
                }
            }
        }
    }
}

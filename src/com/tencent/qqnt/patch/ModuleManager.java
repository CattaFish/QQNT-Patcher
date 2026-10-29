package com.tencent.qqnt.patch;

import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.PatchAssetHelper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModuleManager {
    private static final String TAG = "ModuleManager";
    private static final List<IPatchModule> sModules = new CopyOnWriteArrayList<>();
    private static volatile boolean sInitialized = false;

    public static void register(IPatchModule module) {
        if (module == null) return;
        if (!FeatureConfig.has(module.getId())) {
            return;
        }
        if (!sModules.contains(module)) {
            sModules.add(module);
            PLog.d(TAG, "注册模块 -> [" + module.getName() + "] (ID: " + module.getId() + ")");
        }
    }

    public static List<IPatchModule> getModules() {
        return Collections.unmodifiableList(sModules);
    }

    private static void autoAssembleModules(Context context) {
        if (!sModules.isEmpty()) return;

        try (InputStream is = PatchAssetHelper.openStream(context, "zzz_modules.list")) {
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                ClassLoader cl = context.getClassLoader();
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    try {
                        Class<?> clz = cl.loadClass(line);
                        if (IPatchModule.class.isAssignableFrom(clz)) {
                            IPatchModule inst = (IPatchModule) clz.getDeclaredConstructor().newInstance();
                            register(inst);
                        }
                    } catch (Throwable t) {
                        PLog.w(TAG, "反射加载模块失败 [" + line + "]: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "读取 zzz_modules.list 异常: " + t.getMessage());
        }

        if (sModules.isEmpty()) {
            registerFallbackBuiltinModules();
        }
    }

    private static void registerFallbackBuiltinModules() {
        register(new com.tencent.qqnt.patch.modules.chat.AntiRevokeModule());
        register(new com.tencent.qqnt.patch.modules.chat.FlashPicModule());
        register(new com.tencent.qqnt.patch.modules.chat.MeowModule());
        register(new com.tencent.qqnt.patch.modules.chat.FloatingBallModule());
        register(new com.tencent.qqnt.patch.modules.purify.TabletModeModule());
        register(new com.tencent.qqnt.patch.modules.purify.AtAllNotifyBlockModule());
        register(new com.tencent.qqnt.patch.modules.chat.AutoRemarkApkModule());
        register(new com.tencent.qqnt.patch.modules.chat.ShowDownloadTimesModule());
        register(new com.tencent.qqnt.patch.modules.chat.ModifyPicSummaryModule());
        register(new com.tencent.qqnt.patch.modules.purify.DisableSplitScreenLimitModule());
        register(new com.tencent.qqnt.patch.modules.chat.TgStickerModule());
        register(new com.tencent.qqnt.patch.modules.purify.BrowserMitigationModule());
        register(new com.tencent.qqnt.patch.modules.purify.DisableUpgradeModule());
        register(new com.tencent.qqnt.patch.modules.chat.QQVersionModule());
        register(new com.tencent.qqnt.patch.modules.chat.DoubleTapRepeatModule());
        register(new com.tencent.qqnt.patch.modules.chat.UnlockSwipeReplyModule());
        register(new com.tencent.qqnt.patch.modules.chat.RecordsReplyCheckModule());
        register(new com.tencent.qqnt.patch.modules.chat.RemoveReplyAtModule());
        register(new com.tencent.qqnt.patch.modules.chat.ForwardPttModule());
    }

    public static void initAll(Context context) {
        if (sInitialized || context == null) return;
        sInitialized = true;

        autoAssembleModules(context);

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

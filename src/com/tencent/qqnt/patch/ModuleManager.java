package com.tencent.qqnt.patch;

import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.modules.chat.*;
import com.tencent.qqnt.patch.modules.purify.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModuleManager {
    private static final String TAG = "ModuleManager";
    private static final List<IPatchModule> sModules = new CopyOnWriteArrayList<>();
    private static volatile boolean sInitialized = false;

    private static final Map<String, String> MOD_TO_FEATURE = new HashMap<>();
    static {
        MOD_TO_FEATURE.put("anti_revoke", "anti_revoke");
        MOD_TO_FEATURE.put("flash_pic", "flash_pic");
        MOD_TO_FEATURE.put("meow_helper", "meow");
        MOD_TO_FEATURE.put("floating_ball", "floating_ball");
        MOD_TO_FEATURE.put("tablet_mode", "tablet");
        MOD_TO_FEATURE.put("block_at_all_notify", "block_at_all");
        MOD_TO_FEATURE.put("auto_remark_apk", "auto_remark_apk");
        MOD_TO_FEATURE.put("show_file_download_count", "group_file");
        MOD_TO_FEATURE.put("modify_pic_summary", "modify_pic_summary");
        MOD_TO_FEATURE.put("disable_split_screen_limit", "multi_window");
        MOD_TO_FEATURE.put("tg_stickers", "tg_stickers");
        MOD_TO_FEATURE.put("browser_mitigation", "browser");
    }

    static {
        register(new AntiRevokeModule());
        register(new FlashPicModule());
        register(new MeowModule());
        register(new FloatingBallModule());
        register(new TabletModeModule());
        register(new AtAllNotifyBlockModule());
        register(new AutoRemarkApkModule());
        register(new ShowDownloadTimesModule());
        register(new ModifyPicSummaryModule());
        register(new DisableSplitScreenLimitModule());
        register(new TgStickerModule());
        register(new BrowserMitigationModule());
    }

    public static void register(IPatchModule module) {
        if (module == null) return;
        String fId = MOD_TO_FEATURE.get(module.getId());
        if (fId != null && !FeatureConfig.has(fId)) {
            return;
        }
        if (!sModules.contains(module)) {
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

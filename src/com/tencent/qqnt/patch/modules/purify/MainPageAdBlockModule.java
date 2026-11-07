package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainPageAdBlockModule implements IPatchModule {

    private static final String TAG = "MainPageAdBlock";
    public static final String FEATURE_ID = "main_ad";

    // 1. 系统核心功能白名单（极度稳定，绝对放行，严禁误伤）
    private static final Set<String> SYSTEM_WHITELIST = new HashSet<>(Arrays.asList(
            "NetStateBannerProcessor",               // 断网提示
            "IllegalNetworkBannerProcessor",         // 网络受限
            "NetNeedSignOnBannerProcessor",          // WiFi 网页认证登录提示
            "LoginDevicesBannerProcessor",           // PC / Mac / 平板多端登录状态条
            "PCOnlineBannerProcessor",               // 电脑在线文件互传
            "MultiVideoBannerProcessor",             // 音视频通话 / 群电话进行中悬浮条
            "MissedCallBannerProcessor",             // 未接来电通知
            "MusicPlayerBannerProcessor",            // 听歌 / 音乐播放控制条
            "ExportFileBannerProcessor",             // 文件导出进度条
            "FlashTransferErrBannerProcessor",       // 闪传传输异常
            "FlashTransferProgressBannerProcessor",  // 闪传传输进度
            "MsgBackupBannerProcessor",              // 聊天记录备份中
            "NotificationGuideBannerProcessor",      // 系统通知权限引导
            "PhoneUnityBannerProcessor",             // 密保手机验证
            "MobileUnityBannerProcessor",
            "SecureModifyPwdNotifyBannerProcessor",  // 密码修改安全提示
            "SecurePhoneChangeNotifyBannerProcessor",
            "SetPwdBannerProcessor",
            "VerifyPhoneBannerProcessor"
    ));

    // 2. 营销特征泛化关键词（凡未命中系统白名单、且命中以下特征的，全部就地灭杀）
    private static final String[] AD_KEYWORDS = new String[]{
            "VasAD",        // VIP 商业化广告小条
            "Renewals",     // 会员 / SVIP 到期续费提示
            "GameCenter",   // 游戏中心通知 / 提示条
            "GameBanner",   // 新游活动大横幅
            "Game",         // 泛游戏推广
            "AdDownloader", // 推广应用下载
            "Comic",        // 波洞动漫推广
            "VasZplan",     // 超级QQ秀 / 厘米秀运营推广
            "Running",      // 运动健康打卡推广
            "MiniBox",      // 小游戏盒子推广
            "DressUp",      // 个性装扮 / 商城推广
            "Theme",        // 主题更新 / 过期续订
            "PushBanner",   // 运营强推横幅
            "GeneralBanner",// 通用运营广告
            "MiniGame"      // 小游戏资源条
    };

    private static final Map<Integer, String> sProcessorNameCache = new ConcurrentHashMap<>();

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "隐藏主页横幅广告";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public String getSubName() {
        return "去除消息列表顶部的VIP续费与游戏推广横幅";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 严格遵守默认关闭原则
    }

    @Override
    public int getOrder() {
        return 12; // 紧随群待办 (11) 之后排列
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled(FEATURE_ID, false);
    }

    /**
     * 【咽喉要道 1】：状态机总线拦截 (由 BannerManager->D(II) 入口调用)
     * 当 state >= 2 (试图展示) 时，动态判定处理器类型。若属于广告则强制改写为 0 (HIDE)
     */
    public static int filterBannerState(Object bannerManagerObj, int bannerId, int state) {
        if (!isModuleActive() || bannerManagerObj == null || state < 2) {
            return state;
        }

        try {
            String processorName = resolveProcessorName(bannerManagerObj, bannerId);
            if (processorName == null || processorName.isEmpty()) {
                return state;
            }

            // A. 系统核心白名单 -> 绝对放行
            if (SYSTEM_WHITELIST.contains(processorName)) {
                return state;
            }

            // B. 泛化特征命中广告黑名单 -> 强制状态归 0 (HIDE)
            if (isAdProcessor(processorName)) {
                PLog.i(TAG, "已成功拦截主页横幅推广: " + processorName + " (id=" + bannerId + ")");
                return 0; // 官方逻辑会将 banner.b 设为 0 并调用 notifyBannerDismiss
            }
        } catch (Throwable t) {
            PLog.w(TAG, "filterBannerState 异常: " + t.getMessage());
        }

        return state;
    }

    /**
     * 【源头致盲 2】：天书广告请求拦截 (由 TianShuManager->isRequestEnable(I) 调用)
     * 针对 VIP 广告横幅位 (0x2cb / 715)，直接返回 true 通知阻断，彻底不发网络请求
     */
    public static boolean shouldBlockTianShuPos(int posId) {
        if (!isModuleActive()) {
            return false;
        }
        // 715 (0x2cb): 天书主页 VIP Banner 广告位
        return (posId == 0x2cb || posId == 715);
    }

    private static boolean isAdProcessor(String name) {
        for (String kw : AD_KEYWORDS) {
            if (name.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveProcessorName(Object bannerManagerObj, int bannerId) {
        String cached = sProcessorNameCache.get(bannerId);
        if (cached != null) return cached;

        try {
            // 从 BannerManager.h (ConcurrentHashMap<Integer, BaseBannerProcessor>) 动态反射
            Field hField = bannerManagerObj.getClass().getDeclaredField("h");
            hField.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) hField.get(bannerManagerObj);
            if (map != null) {
                Object processor = map.get(bannerId);
                if (processor != null) {
                    String simpleName = processor.getClass().getSimpleName();
                    sProcessorNameCache.put(bannerId, simpleName);
                    return simpleName;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
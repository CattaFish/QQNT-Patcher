package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.util.Locale;

public class BlockQQShowModule implements IPatchModule {

    private static final String TAG = "BlockQQShow";
    public static final String FEATURE_ID = "block_qq_show";

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "屏蔽超级 QQ 秀";
    }

    @Override
    public String getSubName() {
        return "屏蔽新版 QQ 秀头像、资料卡形象及主页入口";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public int getOrder() {
        return 13;
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled(FEATURE_ID, false);
    }

    /**
     * 抽屉大卡片与顶栏 QQ 秀按钮的官方隐藏分支覆写
     * 模块开启时返回 true，走官方 View.GONE 与重排逻辑，不影响点赞与互动区
     */
    public static boolean overrideHideDrawerQQShow(boolean isStudyMode) {
        return isStudyMode || isModuleActive();
    }

    /**
     * 精准过滤消息页标题栏动态按钮配置 (ConversationTitleBtnConfig)
     * 仅拦截 businessId 属于 ZPlan / QQShow 的入口，保留相机等其他正常按钮
     */
    public static boolean shouldBlockTitleButton(Object configObj) {
        if (!isModuleActive() || configObj == null) {
            return false;
        }
        try {
            String desc = configObj.toString();
            if (desc == null || desc.isEmpty()) {
                return false;
            }
            String businessId = extractBusinessId(desc);
            if (businessId != null && !businessId.isEmpty()) {
                String lower = businessId.toLowerCase(Locale.ROOT);
                if (lower.contains("zplan") || lower.contains("qqshow")) {
                    PLog.once(TAG, "title_btn_" + businessId, "已拦截消息页标题栏按钮: " + businessId);
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "shouldBlockTitleButton 异常: " + t.getMessage());
        }
        return false;
    }

    private static String extractBusinessId(String desc) {
        String key = "businessId=";
        int start = desc.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int endComma = desc.indexOf(',', start);
        int endParen = desc.indexOf(')', start);
        int end = -1;
        if (endComma >= 0 && endParen >= 0) {
            end = Math.min(endComma, endParen);
        } else if (endComma >= 0) {
            end = endComma;
        } else if (endParen >= 0) {
            end = endParen;
        }
        if (end <= start) {
            return null;
        }
        return desc.substring(start, end).trim();
    }
}
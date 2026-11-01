package com.tencent.qqnt.patch.modules.purify;

import android.view.View;
import android.view.ViewGroup;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

public class QZoneAdBlockModule implements IPatchModule {

    private static final String TAG = "QZoneAdBlock";

    @Override
    public String getId() {
        return "qzone_ad";
    }

    @Override
    public String getName() {
        return "隐藏空间广告";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public String getSubName() {
        return "隐藏QQ空间说说流与详情页推广广告";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 严格遵守默认关闭原则
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("qzone_ad", false);
    }

    /**
     * 空间广告消隐与数据阻断：
     * 1. 空安全修正 LayoutParams 尺寸为 0x0（杜绝空指针）；
     * 2. 可见性设为 GONE，同时关闭硬件绘制；
     * 3. 递归消隐内部容器，杜绝任何白条；
     * 4. 返回 true，通知 Smali 立即 return-void 终止后续数据解析、图片下载与 GDT 曝光上报。
     */
    public static boolean suppressAdView(View view) {
        if (!isModuleActive() || view == null) {
            return false;
        }

        try {
            // 1. 自身视觉消除与关闭绘制
            view.setVisibility(View.GONE);
            view.setWillNotDraw(true);

            // 2. 空安全尺寸归零，杜绝 NPE 与空白占位
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = 0;
                lp.height = 0;
                view.setLayoutParams(lp);
            } else {
                view.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
            }

            // 3. 若存在子视图（如详情页底部的 LinearLayout 容器），递归全部设为 GONE
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child != null) {
                        child.setVisibility(View.GONE);
                        child.setWillNotDraw(true);
                    }
                }
            }

            PLog.d(TAG, "已成功拦截并阻断空间广告组件: " + view.getClass().getSimpleName());
        } catch (Throwable t) {
            PLog.w(TAG, "suppressAdView 异常: " + t.getMessage());
        }

        return true;
    }
}
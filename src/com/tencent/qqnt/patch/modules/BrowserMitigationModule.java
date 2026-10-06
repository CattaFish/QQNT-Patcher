package com.tencent.qqnt.patch.modules;

import android.os.Bundle;
import android.text.TextUtils;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.PLog;

public class BrowserMitigationModule implements IPatchModule {

    private static final String TAG = "BrowserSecurity";

    @Override public String getId() { return "browser_mitigation"; }
    @Override public String getName() { return "禁用内置浏览器网页拦截"; }
    @Override public String getSubName() { return "允许在内置浏览器直接访问非官方/第三方网页"; }
    @Override public boolean defaultEnabled() { return false; }

    public static void handleWebSecurityCallback(Bundle bundle) {
        if (!ConfigManager.isModuleEnabled("browser_mitigation", false) || bundle == null) {
            return;
        }

        try {
            // result == 0 说明安全校验有回包
            if (bundle.getInt("result", -1) == 0) {
                int jumpResult = bundle.getInt("jumpResult");
                String jumpUrl = bundle.getString("jumpUrl");
                long operationBit = bundle.getLong("operationBit");

                if (jumpResult != 0 || !TextUtils.isEmpty(jumpUrl) || operationBit != 0) {
                    bundle.putInt("jumpResult", 0);
                    bundle.putString("jumpUrl", "");
                    // 清除 forbid-input 等限制位
                    bundle.putLong("operationBit", 0L);

                    PLog.i(TAG, "已成功拦截内置浏览器跳转阻断，原 jumpResult=" + jumpResult 
                            + ", jumpUrl=" + jumpUrl + ", operationBit=" + operationBit);
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "修改 WebSecurity 校验结果异常", t);
        }
    }
}
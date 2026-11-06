package com.tencent.qqnt.patch.modules.purify;

import android.net.Uri;
import android.text.TextUtils;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.net.URLDecoder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class BrowserMitigationModule implements IPatchModule {
    private static final String TAG = "BrowserSecurity";

    // 常见的腾讯安全拦截域名标识
    private static final String BLOCK_HOST = "c.pc.qq.com";

    // 实例级上下文缓存（弱引用，WebView 销毁时自动 GC，杜绝多实例与后台预加载串扰）
    private static final Map<Object, String> sLastTargetUrls = Collections.synchronizedMap(new WeakHashMap<>());
    // 实例级重试计数器（防止死循环熔断）
    private static final Map<Object, Integer> sRedirectCounters = Collections.synchronizedMap(new WeakHashMap<>());

    @Override public String getId() { return "browser"; }
    @Override public String getName() { return "禁用内置浏览器网页拦截"; }
    @Override public String getCategory() { return CATEGORY_PURIFY; }
    @Override public String getSubName() { return "允许在内置浏览器直接访问非官方/第三方网页"; }
    @Override public boolean defaultEnabled() { return false; }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled("browser", false);
    }

    /**
     * 核心 URL 过滤与拦截掉头中枢（由 CustomWebView.loadUrl 入口静态调用）
     * @param webView 当前调用 loadUrl 的 CustomWebView 实例 (p0)
     * @param url     即将被加载的原始入参 URL (p1)
     * @return 最终放行加载的目标 URL
     */
    public static String filterLoadUrl(Object webView, String url) {
        if (!isModuleActive() || url == null || url.isEmpty()) {
            return url;
        }

        // 1. 快速放行内部协议与非 HTTP 协议，绝不触碰 JS 注入与内部资源
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.startsWith("javascript:") || lowerUrl.startsWith("about:") || lowerUrl.startsWith("data:") || lowerUrl.startsWith("file:")) {
            return url;
        }

        // 2. 检查当前是否为腾讯的拦截中转页 (c.pc.qq.com)
        if (url.contains(BLOCK_HOST)) {
            // 熔断保护：检查当前实例是否在短时间内被频繁重定向
            Integer count = sRedirectCounters.get(webView);
            int currentCount = (count != null) ? count : 0;
            if (currentCount >= 2) {
                PLog.w(TAG, "触发防死循环熔断保护，放行原始拦截页: " + url);
                sRedirectCounters.remove(webView);
                return url;
            }

            // 防线 A：无状态反解（从拦截链接自身的 Query 参数直接提取目标真实 URL，零竞态）
            String extracted = extractRealUrlFromBlockUrl(url);
            if (!TextUtils.isEmpty(extracted)) {
                PLog.i(TAG, "成功从拦截链接参数中自解密目标 URL: " + extracted);
                sRedirectCounters.put(webView, currentCount + 1);
                return extracted;
            }

            // 防线 B：实例级上下文回滚（从当前 WebView 上次加载的合法 URL 回滚）
            String lastTarget = sLastTargetUrls.get(webView);
            if (!TextUtils.isEmpty(lastTarget)) {
                PLog.i(TAG, "从 WebView 实例上下文恢复原始目标: " + lastTarget);
                sRedirectCounters.put(webView, currentCount + 1);
                return lastTarget;
            }

            PLog.w(TAG, "命中拦截域名但未能解析出原始目标，保持原样放行");
            return url;
        }

        // 3. 正常网页（http:// 或 https://），更新实例级上下文并重置重试计数器
        if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
            sLastTargetUrls.put(webView, url);
            sRedirectCounters.remove(webView);
        }

        return url;
    }

    /**
     * 健壮解析：从 c.pc.qq.com 中提取真实目标链接
     * 兼容 pfurl / url / target 等多种腾讯历史版本参数
     */
    private static String extractRealUrlFromBlockUrl(String blockUrl) {
        try {
            Uri uri = Uri.parse(blockUrl);
            String[] candidateParams = new String[]{"pfurl", "url", "target", "dest"};

            for (String param : candidateParams) {
                String val = uri.getQueryParameter(param);
                if (!TextUtils.isEmpty(val)) {
                    String decoded = URLDecoder.decode(val, "UTF-8");
                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        return decoded;
                    }
                }
            }
        } catch (Throwable t) {
            PLog.d(TAG, "解析拦截 URL 参数异常: " + t.getMessage());
        }
        return null;
    }
}
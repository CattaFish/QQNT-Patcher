package com.tencent.qqnt.patch.config;

import android.content.Context;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.util.PatchAssetHelper;
import com.tencent.qqnt.patch.util.PLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FeatureConfig {
    private static volatile boolean sLoaded = false;
    private static final Set<String> sActiveFeatures = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean sAllEnabled = false;
    private static volatile boolean sIsOnlyMode = false;

    public static synchronized void ensureLoaded() {
        if (sLoaded) return;
        Context ctx = AppContext.get();
        if (ctx == null) return;
        
        try (InputStream is = PatchAssetHelper.openStream(ctx, "zzz_features.json")) {
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                String jsonStr = sb.toString().trim();
                if (jsonStr.startsWith("{")) {
                    JSONObject obj = new JSONObject(jsonStr);
                    sIsOnlyMode = obj.optBoolean("only_mode", false);
                    JSONArray arr = obj.optJSONArray("features");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            sActiveFeatures.add(arr.getString(i));
                        }
                    }
                } else if (jsonStr.startsWith("[")) {
                    JSONArray arr = new JSONArray(jsonStr);
                    for (int i = 0; i < arr.length(); i++) {
                        sActiveFeatures.add(arr.getString(i));
                    }
                }
                sLoaded = true;
                PLog.i("FeatureConfig", "加载特性清单成功 [onlyMode=" + sIsOnlyMode + ", count=" + sActiveFeatures.size() + "]");
                return;
            }
        } catch (Throwable t) {
            PLog.w("FeatureConfig", "读取特性清单异常，回退为全量启用: " + t.getMessage());
        }

        sAllEnabled = true;
        sLoaded = true;
    }

    public static boolean isOnlyMode() {
        if (!sLoaded) ensureLoaded();
        return sIsOnlyMode;
    }

    public static boolean has(String featureId) {
        if (!sLoaded) ensureLoaded();
        if (sAllEnabled) return true;
        return sActiveFeatures.contains(featureId);
    }
}
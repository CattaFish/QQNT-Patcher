package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModifyPicSummaryModule implements IPatchModule {

    private static final String TAG = "PicSummary";
    private static volatile String sCachedSummary = "";
    private static final ExecutorService sNetWorker = Executors.newSingleThreadExecutor();
    private static volatile boolean sIsFetching = false;
    private static final Random sRandom = new Random();

    private static final List<String> sLocalLines = new ArrayList<>();
    private static volatile long sLocalFileLastModified = -1L;
    private static volatile String sCurrentSourceDesc = "";

    @Override public String getId() { return "modify_pic_summary"; }
    @Override public String getName() { return "修改图片外显"; }
    @Override public String getSubName() {
        if (!isEnabled()) {
            return "自定义发送图片与大表情的外显文本";
        }
        if (sCachedSummary != null && !sCachedSummary.isEmpty()) {
            return (sCurrentSourceDesc.isEmpty() ? "" : sCurrentSourceDesc + ": ") + sCachedSummary;
        }
        return ConfigManager.isPicSummaryUseLocal() ? "本地词库模式(待挑选)" : "指定外显模式(待获取)";
    }

    @Override public boolean defaultEnabled() { return false; }
    @Override public boolean hasConfig() { return true; }

    @Override
    public void onInit(Context context) {
        fetchNextSummary();
    }

    @Override
    public void setEnabled(boolean enabled) {
        IPatchModule.super.setEnabled(enabled);
        if (enabled && (sCachedSummary == null || sCachedSummary.isEmpty())) {
            fetchNextSummary();
        }
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 图片外显配置", "配置", true, false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled() || elements == null || elements.isEmpty()) return;

        final String summary = sCachedSummary;
        if (summary == null || summary.isEmpty()) return;

        boolean hasPic = false;
        for (MsgElement element : elements) {
            if (element == null) continue;

            if (element.picElement != null) {
                element.picElement.summary = summary;
                hasPic = true;
            }

            try {
                if (element.marketFaceElement != null) {
                    element.marketFaceElement.faceName = summary;
                    hasPic = true;
                }
            } catch (Throwable ignored) {}
        }

        if (hasPic) {
            PLog.i(TAG, "成功注入图片/表情外显: " + summary);
            fetchNextSummary();
        }
    }

    public static void fetchNextSummary() {
        boolean useLocal = ConfigManager.isPicSummaryUseLocal();

        if (useLocal) {
            final String customPath = ConfigManager.getPicSummaryUrl().trim();
            File localTxt = resolveLocalTxtFile(customPath);
            if (localTxt != null && localTxt.exists() && localTxt.isFile()) {
                loadFromLocalFile(localTxt);
            } else {
                sCachedSummary = "";
                sCurrentSourceDesc = "未找到本地词库";
                PLog.w(TAG, "本地词库模式已开启，但在 zzz/ 目录下未找到 .txt 文件");
            }
        } else {
            final String configInput = ConfigManager.getPicSummaryUrl().trim();
            if (configInput.isEmpty()) {
                sCurrentSourceDesc = "";
                sCachedSummary = "";
                return;
            }

            if (configInput.startsWith("http://") || configInput.startsWith("https://")) {
                sCurrentSourceDesc = "API轮询";
                fetchFromHttpApi(configInput);
            } else {
                sCurrentSourceDesc = "指定外显";
                sCachedSummary = configInput.length() <= 30 ? configInput : configInput.substring(0, 30);
            }
        }
    }

    private static File resolveLocalTxtFile(String configInput) {
        if (configInput.toLowerCase().endsWith(".txt")) {
            File f = new File(configInput);
            if (f.exists() && f.isFile()) return f;
        }

        File zzzDir = getZzzBaseDir();
        if (zzzDir.exists() && zzzDir.isDirectory()) {
            File defaultTxt = new File(zzzDir, "summary.txt");
            if (defaultTxt.exists() && defaultTxt.isFile()) {
                return defaultTxt;
            }

            File[] files = zzzDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".txt") && !f.getName().startsWith(".")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private static synchronized void loadFromLocalFile(File txtFile) {
        try {
            long lastMod = txtFile.lastModified();
            if (lastMod != sLocalFileLastModified || sLocalLines.isEmpty()) {
                sLocalLines.clear();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(txtFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("\uFEFF")) {
                            line = line.substring(1);
                        }
                        line = line.trim();
                        if (!line.isEmpty()) {
                            sLocalLines.add(line);
                        }
                    }
                }
                sLocalFileLastModified = lastMod;
                PLog.i(TAG, "装载本地词库 [" + txtFile.getName() + "] 成功，有效行数: " + sLocalLines.size());
            }

            if (!sLocalLines.isEmpty()) {
                int index = sRandom.nextInt(sLocalLines.size());
                String picked = sLocalLines.get(index);
                sCachedSummary = picked.length() <= 30 ? picked : picked.substring(0, 30);
                sCurrentSourceDesc = "本地词库(" + txtFile.getName() + ")";
            } else {
                sCachedSummary = "";
                sCurrentSourceDesc = "本地文件为空";
            }
        } catch (Throwable t) {
            PLog.e(TAG, "读取本地词库异常", t);
        }
    }

    private static void fetchFromHttpApi(String apiUrl) {
        final String key = ConfigManager.getPicSummaryKey().trim();

        if (sIsFetching) return;
        sIsFetching = true;

        sNetWorker.execute(() -> {
            try {
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) QQNT-Patcher");
                conn.setRequestProperty("Accept", "*/*");

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    String response = sb.toString().trim();

                    if (!key.isEmpty()) {
                        Object json = response.startsWith("[") ? new JSONArray(response) : new JSONObject(response);
                        Object val = findFirstValueByKey(json, key);
                        String strVal = val != null ? val.toString().trim() : "";
                        sCachedSummary = strVal.length() <= 30 ? strVal : strVal.substring(0, 30);
                    } else {
                        if (response.length() <= 30) {
                            sCachedSummary = response;
                        } else {
                            sCachedSummary = apiUrl.length() <= 30 ? apiUrl : "";
                        }
                    }
                    PLog.d(TAG, "预拉取下条 API 外显: " + sCachedSummary);
                }
            } catch (Throwable t) {
                PLog.w(TAG, "拉取 API 图片外显异常: " + t.getMessage());
            } finally {
                sIsFetching = false;
            }
        });
    }

    private static Object findFirstValueByKey(Object json, String targetKey) {
        if (json == null || targetKey == null || targetKey.isEmpty()) return null;
        if (json instanceof JSONObject) {
            JSONObject obj = (JSONObject) json;
            if (obj.has(targetKey)) {
                return obj.opt(targetKey);
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object child = obj.opt(k);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    Object res = findFirstValueByKey(child, targetKey);
                    if (res != null) return res;
                }
            }
        } else if (json instanceof JSONArray) {
            JSONArray arr = (JSONArray) json;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    Object res = findFirstValueByKey(child, targetKey);
                    if (res != null) return res;
                }
            }
        }
        return null;
    }

    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;

        final boolean[] isLocalMode = new boolean[]{ ConfigManager.isPicSummaryUseLocal() };

        SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "设置图片外显");

        builder.addSwitch("使用本地词库模式", "开启：从本地 .txt 词库中随机抽取", isLocalMode[0], checked -> {
            isLocalMode[0] = checked;
            EditText etKey = builder.getInputField(1);
            if (etKey != null) {
                etKey.setVisibility(checked ? View.GONE : View.VISIBLE);
            }
            EditText etUrl = builder.getInputField(0);
            if (etUrl != null) {
                etUrl.setHint(checked ? "自定义本地.txt绝对路径 (留空默认读 zzz/*.txt)" : "普通外显文本 或 http(s):// API 接口");
            }
        });

        builder.addInput(
                isLocalMode[0] ? "自定义本地.txt绝对路径 (留空默认读 zzz/*.txt)" : "普通外显文本 或 http(s):// API 接口",
                ConfigManager.getPicSummaryUrl(),
                true, false, null
        );

        builder.addInput(
                "JSON 提取 Key (仅 API 模式生效，支持嵌套查找)",
                ConfigManager.getPicSummaryKey(),
                true, false, null
        );

        EditText etKey = builder.getInputField(1);
        if (etKey != null && isLocalMode[0]) {
            etKey.setVisibility(View.GONE);
        }

        builder.setPositiveButton("保存并更新", dialog -> {
            boolean useLocal = isLocalMode[0];
            String newUrl = builder.getInputValue(0);
            String newKey = builder.getInputValue(1);

            ConfigManager.setPicSummaryUseLocal(useLocal);
            ConfigManager.setPicSummaryUrl(newUrl);
            ConfigManager.setPicSummaryKey(newKey);
            sLocalFileLastModified = -1L;
            fetchNextSummary();

            dialog.dismiss();
            ToastHelper.show(activity, "已保存图片外显配置");
            if (onSaved != null) onSaved.run();
        }).show();
    }

    private static File getZzzBaseDir() {
        Context context = AppContext.get();
        File mediaDir = null;
        if (context != null) {
            try {
                File[] mediaDirs = context.getExternalMediaDirs();
                if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                    mediaDir = mediaDirs[0];
                }
            } catch (Throwable ignored) {}
            if (mediaDir == null) {
                mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + context.getPackageName());
            }
        } else {
            mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/com.tencent.mobileqq");
        }
        File zzzDir = new File(mediaDir, "zzz");
        if (!zzzDir.exists()) zzzDir.mkdirs();
        return zzzDir;
    }
}

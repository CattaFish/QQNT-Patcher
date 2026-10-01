package com.tencent.qqnt.patch.modules;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.NativeSettingHelper;
import com.tencent.qqnt.patch.PLog;
import com.tencent.qqnt.patch.ToastHelper;
import me.yxp.qfun.utils.ui.ThemeHelper;

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

    // 本地词库缓存
    private static final List<String> sLocalLines = new ArrayList<>();
    private static volatile long sLocalFileLastModified = -1L;
    private static volatile String sCurrentSourceDesc = "";

    @Override
    public String getId() {
        return "modify_pic_summary";
    }

    @Override
    public String getName() {
        return "修改图片外显";
    }

    @Override
    public String getSubName() {
        if (!isEnabled()) {
            return "自定义发送图片与大表情的外显文本";
        }
        if (sCachedSummary != null && !sCachedSummary.isEmpty()) {
            return (sCurrentSourceDesc.isEmpty() ? "" : sCurrentSourceDesc + ": ") + sCachedSummary;
        }
        return ConfigManager.isPicSummaryUseLocal() ? "本地词库模式(待挑选)" : "指定外显模式(待获取)";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public boolean hasConfig() {
        return true;
    }

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

    // =========================================================================
    // 设置界面：仅保留唯一的配置入口，彻底去除多余按钮
    // =========================================================================
    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl,
                "  ↳ 图片外显配置",
                "配置",
                true,
                false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    // =========================================================================
    // 拦截发包并注入 Summary
    // =========================================================================
    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled() || elements == null || elements.isEmpty()) return;

        final String summary = sCachedSummary;
        if (summary == null || summary.isEmpty()) return;

        boolean hasPic = false;
        for (MsgElement element : elements) {
            if (element == null) continue;

            // 普通图片
            if (element.picElement != null) {
                element.picElement.summary = summary;
                hasPic = true;
            }

            // 商城大表情
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

    // =========================================================================
    // 调度引擎（按开关严格区分模式）
    // =========================================================================
    public static void fetchNextSummary() {
        boolean useLocal = ConfigManager.isPicSummaryUseLocal();

        if (useLocal) {
            // ===== 模式 A: 本地词库模式 =====
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
            // ===== 模式 B: 指定界面外显模式 (API 或 固定文本) =====
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
                        // 清除 UTF-8 BOM 与空白字符
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

    // =========================================================================
    // 弹窗配置（高清晰拟物化开关 + 纯文字状态无 Emoji）
    // =========================================================================
    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;

        boolean isNight = ThemeHelper.INSTANCE.isNightMode();

        int bgColor = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int textColor = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        int inputBgColor = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#F2F2F7");

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int pad = dp2px(activity, 20f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp2px(activity, 18f));
        root.setBackground(bg);

        // 标题
        TextView title = new TextView(activity);
        title.setText("设置图片外显");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 14f));
        root.addView(title);

        // 模式切换行 (整行支持点击)
        LinearLayout modeRow = new LinearLayout(activity);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(dp2px(activity, 4f), 0, dp2px(activity, 4f), dp2px(activity, 12f));

        LinearLayout modeTextCol = new LinearLayout(activity);
        modeTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView modeTitle = new TextView(activity);
        modeTitle.setText("使用本地词库模式");
        modeTitle.setTextSize(15);
        modeTitle.getPaint().setFakeBoldText(true);
        modeTitle.setTextColor(textColor);

        TextView modeDesc = new TextView(activity);
        modeDesc.setTextSize(12);
        modeDesc.setTextColor(subTextColor);

        modeTextCol.addView(modeTitle);
        modeTextCol.addView(modeDesc);
        modeRow.addView(modeTextCol, mtLp);

        // ★★★ 核心修复：纯原生自绘高清晰胶囊开关（彻底解决宿主无图元导致的微缩字体问题）★★★
        int trackW = dp2px(activity, 52f);
        int trackH = dp2px(activity, 30f);
        int thumbSize = dp2px(activity, 24f);
        int thumbMargin = dp2px(activity, 3f);

        LinearLayout switchContainer = new LinearLayout(activity);
        switchContainer.setOrientation(LinearLayout.HORIZONTAL);
        switchContainer.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvStatus = new TextView(activity);
        tvStatus.setTextSize(13);
        tvStatus.getPaint().setFakeBoldText(true);
        tvStatus.setPadding(0, 0, dp2px(activity, 8f), 0);
        switchContainer.addView(tvStatus);

        FrameLayout switchTrack = new FrameLayout(activity);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(trackH / 2f);
        switchTrack.setBackground(trackBg);

        View switchThumb = new View(activity);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(Color.WHITE);
        switchThumb.setBackground(thumbBg);

        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(thumbSize, thumbSize);
        thumbLp.gravity = Gravity.CENTER_VERTICAL;
        switchTrack.addView(switchThumb, thumbLp);

        switchContainer.addView(switchTrack, new LinearLayout.LayoutParams(trackW, trackH));
        modeRow.addView(switchContainer);
        root.addView(modeRow);

        // 词库与接口状态文字（无 Emoji）
        TextView statusDesc = new TextView(activity);
        statusDesc.setTextSize(12);
        statusDesc.setPadding(0, 0, 0, dp2px(activity, 12f));
        root.addView(statusDesc);

        // 输入框 1
        EditText etUrl = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "", ConfigManager.getPicSummaryUrl());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f));
        lp1.bottomMargin = dp2px(activity, 10f);
        root.addView(etUrl, lp1);

        // 输入框 2 (JSON Key)
        EditText etKey = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "JSON 提取 Key (仅 API 模式生效，支持深度查找)", ConfigManager.getPicSummaryKey());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f));
        lp2.bottomMargin = dp2px(activity, 16f);
        root.addView(etKey, lp2);

        // 状态变量与联动更新
        final boolean[] isLocalMode = new boolean[]{ ConfigManager.isPicSummaryUseLocal() };

        Runnable updateUIState = () -> {
            boolean isLocal = isLocalMode[0];
            if (isLocal) {
                tvStatus.setText("已开启");
                tvStatus.setTextColor(Color.parseColor("#34C759"));
                trackBg.setColor(Color.parseColor("#34C759"));
                thumbLp.leftMargin = trackW - thumbSize - thumbMargin;

                modeDesc.setText("开启：从本地 .txt 词库中随机抽取");
                File f = resolveLocalTxtFile(etUrl.getText().toString());
                if (f != null && f.exists()) {
                    statusDesc.setText("[已关联词库] " + f.getName());
                    statusDesc.setTextColor(Color.parseColor("#34C759"));
                } else {
                    statusDesc.setText("[提示] 留空默认读取 /zzz/ 目录下的 .txt 词库");
                    statusDesc.setTextColor(subTextColor);
                }
                etUrl.setHint("自定义本地.txt绝对路径 (留空默认读 zzz/*.txt)");
                etKey.setVisibility(View.GONE);
            } else {
                tvStatus.setText("已关闭");
                tvStatus.setTextColor(subTextColor);
                trackBg.setColor(isNight ? Color.parseColor("#3A3A3C") : Color.parseColor("#D1D1D6"));
                thumbLp.leftMargin = thumbMargin;

                modeDesc.setText("关闭：使用下方填写的固定文本或 API 接口");
                statusDesc.setText("[提示] 支持输入固定外显文本(<=30字)或 API 网络接口");
                statusDesc.setTextColor(subTextColor);
                etUrl.setHint("普通外显文本 或 http(s):// API 接口");
                etKey.setVisibility(View.VISIBLE);
            }
            switchThumb.setLayoutParams(thumbLp);
        };

        modeRow.setOnClickListener(v -> {
            isLocalMode[0] = !isLocalMode[0];
            updateUIState.run();
        });
        updateUIState.run();

        // 按钮行
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(14);
        cancelBtn.setTextColor(subTextColor);
        cancelBtn.setAllCaps(false);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(inputBgColor);
        cancelBg.setCornerRadius(dp2px(activity, 10f));
        cancelBtn.setBackground(cancelBg);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        cancelLp.rightMargin = dp2px(activity, 8f);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn, cancelLp);

        Button saveBtn = new Button(activity);
        saveBtn.setText("保存并更新");
        saveBtn.setTextSize(14);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setAllCaps(false);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(Color.parseColor("#007AFF"));
        saveBg.setCornerRadius(dp2px(activity, 10f));
        saveBtn.setBackground(saveBg);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        saveBtn.setOnClickListener(v -> {
            boolean useLocal = isLocalMode[0];
            String newUrl = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
            String newKey = etKey.getText() != null ? etKey.getText().toString().trim() : "";
            ConfigManager.setPicSummaryUseLocal(useLocal);
            ConfigManager.setPicSummaryUrl(newUrl);
            ConfigManager.setPicSummaryKey(newKey);
            sLocalFileLastModified = -1L;
            fetchNextSummary();
            dialog.dismiss();
            ToastHelper.show(activity, "已保存图片外显配置");
            if (onSaved != null) onSaved.run();
        });
        btnRow.addView(saveBtn, saveLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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

    private static EditText createStyledEditText(Context context, int bgCol, int textCol, int hintCol, String hint, String initialText) {
        EditText et = new EditText(context);
        et.setText(initialText != null ? initialText : "");
        et.setHint(hint);
        et.setHintTextColor(hintCol);
        et.setTextColor(textCol);
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setPadding(dp2px(context, 12f), 0, dp2px(context, 12f), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgCol);
        bg.setCornerRadius(dp2px(context, 10f));
        et.setBackground(bg);
        return et;
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
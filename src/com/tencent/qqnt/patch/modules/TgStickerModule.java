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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.mobileqq.data.EmoticonPackage;
import com.tencent.mobileqq.emoticonview.EmotionPanelData;
import com.tencent.mobileqq.emoticonview.EmotionPanelInfo;
import com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.NativeSettingHelper;
import com.tencent.qqnt.patch.PLog;
import com.tencent.qqnt.patch.ToastHelper;
import me.yxp.qfun.utils.ui.ThemeHelper;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TgStickerModule implements IPatchModule {

    private static final String TAG = "TgSticker";
    private static final String EPID_PREFIX = "zzz:local:";
    private static final String ACTION_PREFIX = "zzz:";

    public static final String KEY_REMOVE_QQ_EMOTICONS = "zzz_tg_remove_qq_emoticons";
    public static final String KEY_REMOVE_QQ_MISC      = "zzz_tg_remove_qq_misc";
    public static final String KEY_PANEL_COLUMNS       = "zzz_tg_panel_columns";

    // 严禁包含视频格式，仅放行 QQ 原生图片解码器支持的格式 (支持动态 WebP 与 GIF)
    private static final Set<String> ALLOWED_EXTS = new HashSet<>(
            Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".webp")
    );

    // 面板缓存
    private static final Map<String, StickerPanel> sPanelMap = new ConcurrentHashMap<>();
    private static volatile long sLastScanTime = 0L;
    private static final long SCAN_INTERVAL_MS = 5000L;

    @Override
    public String getId() {
        return "tg_stickers";
    }

    @Override
    public String getName() {
        return "Telegram 表情包集";
    }

    @Override
    public String getSubName() {
        if (!isEnabled()) {
            return "加载 /zzz/stickers/ 目录下的外部表情包";
        }
        int count = sPanelMap.size();
        String summary = "已加载 " + count + " 个表情包 (" + getPanelColumns() + "列)";
        if (isRemoveQQEmoticons() || isRemoveQQMisc()) {
            summary += " · 净化中";
        }
        return summary;
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
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl,
                "  ↳ 表情包配置",
                "配置",
                true,
                false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

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

        TextView title = new TextView(activity);
        title.setText("Telegram 表情包设置");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 10f));
        root.addView(title);

        List<StickerPanel> currentPanels = getPanels();
        TextView statusDesc = new TextView(activity);
        statusDesc.setTextSize(12);
        statusDesc.setText("[路径] /Android/media/.../zzz/stickers/\n[状态] 当前识别 " + currentPanels.size() + " 个表情包 (支持 PNG/JPG/WebP/GIF)");
        statusDesc.setTextColor(Color.parseColor("#34C759"));
        statusDesc.setLineSpacing(dp2px(activity, 2f), 1f);
        statusDesc.setPadding(dp2px(activity, 4f), 0, dp2px(activity, 4f), dp2px(activity, 12f));
        root.addView(statusDesc);

        final int[] tempColumns = new int[]{ getPanelColumns() };
        final boolean[] tempRemoveEmoticons = new boolean[]{ isRemoveQQEmoticons() };
        final boolean[] tempRemoveMisc = new boolean[]{ isRemoveQQMisc() };

        View columnStepperRow = createColumnStepperRow(
                activity, isNight, textColor, subTextColor, inputBgColor,
                tempColumns
        );
        root.addView(columnStepperRow);

        View switchRowA = createCapsuleSwitchRow(
                activity, isNight, textColor, subTextColor,
                "移除 QQ 商店表情",
                "隐藏官方商城下载的大表情，仅保留外部贴纸",
                tempRemoveEmoticons[0],
                checked -> tempRemoveEmoticons[0] = checked
        );
        root.addView(switchRowA);

        View switchRowB = createCapsuleSwitchRow(
                activity, isNight, textColor, subTextColor,
                "移除 QQ 杂项入口",
                "隐藏表情商城加号、GIF热图、动效推广等杂项",
                tempRemoveMisc[0],
                checked -> tempRemoveMisc[0] = checked
        );
        root.addView(switchRowB);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 6f), 0, 0);

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
            setPanelColumns(tempColumns[0]);
            setRemoveQQEmoticons(tempRemoveEmoticons[0]);
            setRemoveQQMisc(tempRemoveMisc[0]);
            sLastScanTime = 0L;
            sPanelMap.clear();
            dialog.dismiss();
            ToastHelper.show(activity, "已保存配置 (当前设定: " + tempColumns[0] + "列)");
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

    private static View createColumnStepperRow(Activity activity, boolean isNight, int textColor, int subTextColor,
                                               int inputBgColor, final int[] tempColumns) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(activity, 4f), dp2px(activity, 4f), dp2px(activity, 4f), dp2px(activity, 12f));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("表情包显示列数");
        tvTitle.setTextSize(15);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setTextColor(textColor);

        TextView tvDesc = new TextView(activity);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(subTextColor);
        tvDesc.setPadding(0, dp2px(activity, 2f), 0, 0);

        textCol.addView(tvTitle);
        textCol.addView(tvDesc);
        row.addView(textCol, tLp);

        LinearLayout stepper = new LinearLayout(activity);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER);
        GradientDrawable stepBg = new GradientDrawable();
        stepBg.setColor(inputBgColor);
        stepBg.setCornerRadius(dp2px(activity, 8f));
        stepper.setBackground(stepBg);
        stepper.setPadding(dp2px(activity, 4f), dp2px(activity, 2f), dp2px(activity, 4f), dp2px(activity, 2f));

        TextView btnMinus = new TextView(activity);
        btnMinus.setText(" - ");
        btnMinus.setTextSize(16);
        btnMinus.getPaint().setFakeBoldText(true);
        btnMinus.setTextColor(Color.parseColor("#007AFF"));
        btnMinus.setPadding(dp2px(activity, 8f), dp2px(activity, 4f), dp2px(activity, 8f), dp2px(activity, 4f));

        TextView tvVal = new TextView(activity);
        tvVal.setTextSize(14);
        tvVal.getPaint().setFakeBoldText(true);
        tvVal.setTextColor(textColor);
        tvVal.setGravity(Gravity.CENTER);
        tvVal.setMinWidth(dp2px(activity, 44f));

        TextView btnPlus = new TextView(activity);
        btnPlus.setText(" + ");
        btnPlus.setTextSize(16);
        btnPlus.getPaint().setFakeBoldText(true);
        btnPlus.setTextColor(Color.parseColor("#007AFF"));
        btnPlus.setPadding(dp2px(activity, 8f), dp2px(activity, 4f), dp2px(activity, 8f), dp2px(activity, 4f));

        Runnable updateDisplay = () -> {
            tvVal.setText(tempColumns[0] + " 列");
            tvDesc.setText("每行排列 " + tempColumns[0] + " 个表情 (范围: 3 ~ 8 列)");
        };

        btnMinus.setOnClickListener(v -> {
            if (tempColumns[0] > 3) {
                tempColumns[0]--;
                updateDisplay.run();
            }
        });

        btnPlus.setOnClickListener(v -> {
            if (tempColumns[0] < 8) {
                tempColumns[0]++;
                updateDisplay.run();
            }
        });

        stepper.addView(btnMinus);
        stepper.addView(tvVal);
        stepper.addView(btnPlus);
        row.addView(stepper);

        updateDisplay.run();
        return row;
    }

    private interface OnSwitchStateListener {
        void onStateChange(boolean state);
    }

    private static View createCapsuleSwitchRow(Activity activity, boolean isNight, int textColor, int subTextColor,
                                               String title, String desc, boolean initialChecked, OnSwitchStateListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(activity, 4f), dp2px(activity, 6f), dp2px(activity, 4f), dp2px(activity, 12f));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setTextColor(textColor);

        TextView tvDesc = new TextView(activity);
        tvDesc.setText(desc);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(subTextColor);
        tvDesc.setPadding(0, dp2px(activity, 2f), 0, 0);

        textCol.addView(tvTitle);
        textCol.addView(tvDesc);
        row.addView(textCol, tLp);

        int trackW = dp2px(activity, 50f);
        int trackH = dp2px(activity, 28f);
        int thumbSize = dp2px(activity, 22f);
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
        row.addView(switchContainer);

        final boolean[] state = new boolean[]{ initialChecked };

        Runnable updateUI = () -> {
            boolean cur = state[0];
            if (cur) {
                tvStatus.setText("已开启");
                tvStatus.setTextColor(Color.parseColor("#34C759"));
                trackBg.setColor(Color.parseColor("#34C759"));
                thumbLp.leftMargin = trackW - thumbSize - thumbMargin;
            } else {
                tvStatus.setText("已关闭");
                tvStatus.setTextColor(subTextColor);
                trackBg.setColor(isNight ? Color.parseColor("#3A3A3C") : Color.parseColor("#D1D1D6"));
                thumbLp.leftMargin = thumbMargin;
            }
            switchThumb.setLayoutParams(thumbLp);
        };

        row.setOnClickListener(v -> {
            state[0] = !state[0];
            updateUI.run();
            if (listener != null) listener.onStateChange(state[0]);
        });

        updateUI.run();
        return row;
    }

    public static int getPanelColumns() {
        String val = ConfigManager.getString(KEY_PANEL_COLUMNS, "5");
        try {
            int c = Integer.parseInt(val.trim());
            if (c >= 3 && c <= 8) return c;
        } catch (Throwable ignored) {}
        return 5;
    }

    public static void setPanelColumns(int columns) {
        ConfigManager.setString(KEY_PANEL_COLUMNS, String.valueOf(columns));
    }

    public static boolean isRemoveQQEmoticons() {
        return ConfigManager.hasFlag(KEY_REMOVE_QQ_EMOTICONS);
    }

    public static void setRemoveQQEmoticons(boolean enable) {
        ConfigManager.setFlag(KEY_REMOVE_QQ_EMOTICONS, enable);
    }

    public static boolean isRemoveQQMisc() {
        return ConfigManager.hasFlag(KEY_REMOVE_QQ_MISC);
    }

    public static void setRemoveQQMisc(boolean enable) {
        ConfigManager.setFlag(KEY_REMOVE_QQ_MISC, enable);
    }

    @Override
    public void onInit(Context context) {
        ensureStickerDir();
    }

    public static File getStickerBaseDir() {
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
        File stickerDir = new File(zzzDir, "stickers");
        return stickerDir;
    }

    private static void ensureStickerDir() {
        try {
            File baseDir = getStickerBaseDir();
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }
            File noMedia = new File(baseDir, ".nomedia");
            if (!noMedia.exists()) {
                noMedia.createNewFile();
            }
        } catch (Throwable t) {
            PLog.w(TAG, "创建表情包目录异常: " + t.getMessage());
        }
    }

    public static synchronized List<StickerPanel> getPanels() {
        long now = System.currentTimeMillis();
        if (now - sLastScanTime < SCAN_INTERVAL_MS && !sPanelMap.isEmpty()) {
            return new ArrayList<>(sPanelMap.values());
        }

        ensureStickerDir();
        File baseDir = getStickerBaseDir();
        File[] subDirs = baseDir.listFiles();

        List<StickerPanel> panels = new ArrayList<>();
        Set<String> currentKeys = new HashSet<>();

        if (subDirs != null) {
            Arrays.sort(subDirs, (f1, f2) -> compareNatural(f1.getName(), f2.getName()));
            for (File dir : subDirs) {
                if (dir.isDirectory() && !dir.getName().startsWith(".")) {
                    String panelId = dir.getName();
                    currentKeys.add(panelId);

                    StickerPanel panel = sPanelMap.get(panelId);
                    if (panel == null || panel.isExpired()) {
                        panel = new StickerPanel(dir, panelId);
                        sPanelMap.put(panelId, panel);
                    }
                    if (!panel.getEmoticons().isEmpty()) {
                        panels.add(panel);
                    }
                }
            }
        }

        sPanelMap.keySet().retainAll(currentKeys);
        sLastScanTime = now;
        return panels;
    }

    // =========================================================================
    // 静态插桩调用 1: 拦截并修改 Tab 列表 (带 Reaction 防御与零卡顿保护)
    // =========================================================================
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List modifyPanelDataList(List originalList) {
        if (!ConfigManager.isModuleEnabled("tg_stickers", false) || originalList == null || originalList.isEmpty()) {
            return originalList;
        }

        // ★ 核心关键防御：如果只有一个元素且为 AIOEmoReply (type 16，消息长按快捷回应)，绝对不处理！
        if (originalList.size() == 1) {
            Object first = originalList.get(0);
            if (first instanceof EmotionPanelInfo && ((EmotionPanelInfo) first).type == 16) {
                return originalList;
            }
        }

        try {
            boolean removeEmoticons = isRemoveQQEmoticons();
            boolean removeMisc = isRemoveQQMisc();

            Set<Integer> baseWhiteList = new HashSet<>(Arrays.asList(18, 7, 1, 4));
            if (!removeMisc) {
                baseWhiteList.addAll(Arrays.asList(13, 12, 17, 19, 21, 14));
            }

            // 净化过滤
            Iterator iterator = originalList.iterator();
            while (iterator.hasNext()) {
                Object itemObj = iterator.next();
                if (!(itemObj instanceof EmotionPanelInfo)) continue;
                EmotionPanelInfo info = (EmotionPanelInfo) itemObj;

                if (info.emotionPkg != null && info.emotionPkg.epId != null && info.emotionPkg.epId.startsWith(EPID_PREFIX)) {
                    continue;
                }

                if (info.type == 6 || info.type == 10) {
                    if (removeEmoticons) {
                        iterator.remove();
                    }
                    continue;
                }

                if (!baseWhiteList.contains(info.type)) {
                    iterator.remove();
                }
            }

            List<StickerPanel> panels = getPanels();
            if (panels.isEmpty()) return originalList;

            int columnNum = getPanelColumns();

            // 查找插入点
            int targetInsertIndex = -1;
            for (int i = 0; i < originalList.size(); i++) {
                Object itemObj = originalList.get(i);
                if (itemObj instanceof EmotionPanelInfo) {
                    EmotionPanelInfo item = (EmotionPanelInfo) itemObj;
                    if ((item.type == 6 || item.type == 10) && item.emotionPkg != null && item.emotionPkg.epId != null) {
                        if (!item.emotionPkg.epId.startsWith(EPID_PREFIX)) {
                            targetInsertIndex = i;
                            break;
                        }
                    }
                }
            }

            if (targetInsertIndex < 0) {
                for (int i = 0; i < originalList.size(); i++) {
                    Object itemObj = originalList.get(i);
                    if (itemObj instanceof EmotionPanelInfo && ((EmotionPanelInfo) itemObj).type == 4) {
                        targetInsertIndex = i + 1;
                        break;
                    }
                }
            }

            if (targetInsertIndex < 0) {
                for (int i = 0; i < originalList.size(); i++) {
                    Object itemObj = originalList.get(i);
                    if (itemObj instanceof EmotionPanelInfo && ((EmotionPanelInfo) itemObj).type == 12) {
                        targetInsertIndex = i + 1;
                        break;
                    }
                }
            }

            if (targetInsertIndex < 0) {
                for (int i = 0; i < originalList.size(); i++) {
                    Object itemObj = originalList.get(i);
                    if (itemObj instanceof EmotionPanelInfo) {
                        int t = ((EmotionPanelInfo) itemObj).type;
                        if (t == 13 || t == 14 || t == 8) {
                            targetInsertIndex = i;
                            break;
                        }
                    }
                }
            }

            if (targetInsertIndex < 0) {
                targetInsertIndex = originalList.size();
            }

            Set<String> existingEpIds = new HashSet<>();
            for (Object itemObj : originalList) {
                if (itemObj instanceof EmotionPanelInfo) {
                    EmotionPanelInfo item = (EmotionPanelInfo) itemObj;
                    if (item.emotionPkg != null && item.emotionPkg.epId != null) {
                        existingEpIds.add(item.emotionPkg.epId);
                    }
                }
            }

            int offset = 0;
            for (StickerPanel panel : panels) {
                String epId = panel.getEpId();
                if (existingEpIds.contains(epId)) continue;

                EmotionPanelInfo panelInfo = new EmotionPanelInfo(6, columnNum, panel.getEmoticonPackage());
                int insertPos = Math.min(targetInsertIndex + offset, originalList.size());
                originalList.add(insertPos, panelInfo);
                existingEpIds.add(epId);
                offset++;
            }

        } catch (Throwable t) {
            PLog.e(TAG, "modifyPanelDataList 异常", t);
        }

        return originalList;
    }

    public static List<EmotionPanelData> getEmoticonData(Object emotionPanelInfoObj) {
        if (emotionPanelInfoObj instanceof EmotionPanelInfo) {
            EmotionPanelInfo info = (EmotionPanelInfo) emotionPanelInfoObj;
            if (info.emotionPkg != null && info.emotionPkg.epId != null) {
                String epId = info.emotionPkg.epId;
                if (epId.startsWith(EPID_PREFIX)) {
                    String panelId = epId.substring(EPID_PREFIX.length());
                    StickerPanel panel = sPanelMap.get(panelId);
                    if (panel != null) {
                        List<FavoriteEmoticonInfo> emos = panel.getEmoticons();
                        return new ArrayList<>(emos);
                    }
                }
            }
        }
        return null;
    }

    public static boolean isTgEmoticonPackage(Object pkgObj) {
        if (pkgObj instanceof EmoticonPackage) {
            EmoticonPackage pkg = (EmoticonPackage) pkgObj;
            return pkg.epId != null && pkg.epId.startsWith(EPID_PREFIX);
        }
        return false;
    }

    public static URL getTabUrl(String epId) {
        if (epId != null && epId.startsWith(EPID_PREFIX)) {
            String panelId = epId.substring(EPID_PREFIX.length());
            StickerPanel panel = sPanelMap.get(panelId);
            if (panel != null) {
                String cover = panel.getCoverPath();
                if (cover != null && !cover.isEmpty()) {
                    try {
                        return new URL("file://" + cover);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    public static boolean isTgFavoriteEmoticon(Object favInfoObj) {
        if (favInfoObj instanceof FavoriteEmoticonInfo) {
            FavoriteEmoticonInfo fav = (FavoriteEmoticonInfo) favInfoObj;
            return fav.actionData != null && fav.actionData.startsWith(ACTION_PREFIX);
        }
        return false;
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // =========================================================================
    // 极速自然数字排序算法 (0 对象创建，纯字符流计算，杜绝卡顿)
    // =========================================================================
    public static int compareNatural(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;
        int i = 0, j = 0;
        int len1 = s1.length(), len2 = s2.length();
        while (i < len1 && j < len2) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(j);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                long n1 = 0;
                while (i < len1 && Character.isDigit(s1.charAt(i))) {
                    n1 = n1 * 10 + (s1.charAt(i) - '0');
                    i++;
                }
                long n2 = 0;
                while (j < len2 && Character.isDigit(s2.charAt(j))) {
                    n2 = n2 * 10 + (s2.charAt(j) - '0');
                    j++;
                }
                if (n1 != n2) {
                    return Long.compare(n1, n2);
                }
            } else {
                char l1 = Character.toLowerCase(c1);
                char l2 = Character.toLowerCase(c2);
                if (l1 != l2) {
                    return l1 - l2;
                }
                i++;
                j++;
            }
        }
        return len1 - len2;
    }

    // =========================================================================
    // 贴纸面板数据管理内部类 (纯图片秒级扫描，杜绝视频阻塞)
    // =========================================================================
    public static class StickerPanel {
        private final File dir;
        private final String panelId;
        private final String epId;
        private final EmoticonPackage pkg;
        private String coverPath = null;
        private long lastModified = 0L;
        private List<FavoriteEmoticonInfo> emoticons = new ArrayList<>();

        public StickerPanel(File dir, String panelId) {
            this.dir = dir;
            this.panelId = panelId;
            this.epId = EPID_PREFIX + panelId;

            this.pkg = new EmoticonPackage();
            this.pkg.epId = this.epId;
            this.pkg.name = panelId;
            this.pkg.type = 3;
            this.pkg.status = 2;
            this.pkg.valid = true;
            this.pkg.aio = true;
            this.pkg.latestVersion = 1488377358;

            refreshFiles();
        }

        public boolean isExpired() {
            return dir.exists() && dir.lastModified() != this.lastModified;
        }

        public String getEpId() { return epId; }
        public String getCoverPath() { return coverPath; }
        public EmoticonPackage getEmoticonPackage() { return pkg; }
        public List<FavoriteEmoticonInfo> getEmoticons() { return emoticons; }

        private void refreshFiles() {
            if (!dir.exists() || !dir.isDirectory()) return;
            this.lastModified = dir.lastModified();

            File[] files = dir.listFiles();
            List<FavoriteEmoticonInfo> list = new ArrayList<>();
            coverPath = null;

            if (files != null) {
                Arrays.sort(files, (a, b) -> compareNatural(a.getName(), b.getName()));

                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith(".") || name.endsWith(".nomedia") || name.endsWith(".txt.jpg")) continue;

                    int dotIdx = name.lastIndexOf(".");
                    if (dotIdx == -1) continue;
                    String ext = name.substring(dotIdx).toLowerCase();
                    // 仅收录合法图片文件，遇到 .webm 或其他非图片文件安全忽略，绝不卡死
                    if (!ALLOWED_EXTS.contains(ext)) continue;

                    if (name.startsWith("__cover__.")) {
                        coverPath = f.getAbsolutePath();
                        continue;
                    }

                    FavoriteEmoticonInfo emo = new FavoriteEmoticonInfo();
                    emo.path = f.getAbsolutePath();
                    emo.actionData = ACTION_PREFIX + panelId + ":" + f.getAbsolutePath();
                    list.add(emo);
                }

                if (coverPath == null && !list.isEmpty()) {
                    coverPath = list.get(0).path;
                }
            }
            this.emoticons = list;
        }
    }
}
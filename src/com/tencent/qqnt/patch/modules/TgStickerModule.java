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

    private static final Set<String> ALLOWED_EXTS = new HashSet<>(
            Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".webp")
    );

    // 面板缓存
    private static final Map<String, StickerPanel> sPanelMap = new ConcurrentHashMap<>();
    private static volatile long sLastScanTime = 0L;
    private static final long SCAN_INTERVAL_MS = 3000L; // 3 秒内避免频繁扫盘

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
        int count = getPanels().size();
        String summary = "已加载 " + count + " 个表情包";
        if (isRemoveQQEmoticons() || isRemoveQQMisc()) {
            summary += " (净化已开启)";
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

    // =========================================================================
    // 外面仅保留一个唯一的配置入口 (与修改图片外显完全对齐)
    // =========================================================================
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

    // =========================================================================
    // 沉浸式高级配置弹窗 (卡片拟物化胶囊开关，自适应夜间模式)
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

        // 1. 标题
        TextView title = new TextView(activity);
        title.setText("Telegram 表情包设置");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 10f));
        root.addView(title);

        // 2. 状态与存储路径信息栏
        List<StickerPanel> currentPanels = getPanels();
        TextView statusDesc = new TextView(activity);
        statusDesc.setTextSize(12);
        statusDesc.setText("[路径] /Android/media/.../zzz/stickers/\n[状态] 当前已识别 " + currentPanels.size() + " 个表情包文件夹");
        statusDesc.setTextColor(Color.parseColor("#34C759"));
        statusDesc.setLineSpacing(dp2px(activity, 2f), 1f);
        statusDesc.setPadding(dp2px(activity, 4f), 0, dp2px(activity, 4f), dp2px(activity, 14f));
        root.addView(statusDesc);

        // 3. 状态变量暂存
        final boolean[] tempRemoveEmoticons = new boolean[]{ isRemoveQQEmoticons() };
        final boolean[] tempRemoveMisc = new boolean[]{ isRemoveQQMisc() };

        // 4. 开关行 A: 移除 QQ 商店表情
        View switchRowA = createCapsuleSwitchRow(
                activity, isNight, textColor, subTextColor,
                "移除 QQ 商店表情",
                "隐藏官方商城下载的大表情，仅保留外部贴纸",
                tempRemoveEmoticons[0],
                checked -> tempRemoveEmoticons[0] = checked
        );
        root.addView(switchRowA);

        // 5. 开关行 B: 移除 QQ 杂项入口
        View switchRowB = createCapsuleSwitchRow(
                activity, isNight, textColor, subTextColor,
                "移除 QQ 杂项入口",
                "隐藏表情商城加号、GIF热图、动效推广等杂项",
                tempRemoveMisc[0],
                checked -> tempRemoveMisc[0] = checked
        );
        root.addView(switchRowB);

        // 6. 底部操作按钮栏
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
            setRemoveQQEmoticons(tempRemoveEmoticons[0]);
            setRemoveQQMisc(tempRemoveMisc[0]);
            sLastScanTime = 0L; // 立即重置缓存
            dialog.dismiss();
            ToastHelper.show(activity, "已保存表情包配置");
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

        // 拟物化高清晰自绘胶囊开关
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
            Arrays.sort(subDirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
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
    // 静态插桩调用 1: 拦截并修改 Tab 列表 (执行净化过滤 + 插入 TG 贴纸)
    // =========================================================================
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List modifyPanelDataList(List originalList) {
        if (!ConfigManager.isModuleEnabled("tg_stickers", false) || originalList == null || originalList.isEmpty()) {
            return originalList;
        }

        try {
            boolean removeEmoticons = isRemoveQQEmoticons();
            boolean removeMisc = isRemoveQQMisc();

            // 基础白名单：系统 Emoji/小黄脸(7/1), 收藏表情(4), 搜索(18)
            Set<Integer> baseWhiteList = new HashSet<>(Arrays.asList(18, 7, 1, 4));
            if (!removeMisc) {
                baseWhiteList.addAll(Arrays.asList(13, 12, 17, 19, 21, 14));
            }

            // -----------------------------------------------------------------
            // 阶段 A: 执行净化过滤
            // -----------------------------------------------------------------
            Iterator iterator = originalList.iterator();
            while (iterator.hasNext()) {
                Object itemObj = iterator.next();
                if (!(itemObj instanceof EmotionPanelInfo)) continue;
                EmotionPanelInfo info = (EmotionPanelInfo) itemObj;

                // 自身注入的贴纸包不参与净化删除
                if (info.emotionPkg != null && info.emotionPkg.epId != null && info.emotionPkg.epId.startsWith(EPID_PREFIX)) {
                    continue;
                }

                // 1. 过滤商城大表情 (type == 6 或 10)
                if (info.type == 6 || info.type == 10) {
                    if (removeEmoticons) {
                        iterator.remove();
                    }
                    continue;
                }

                // 2. 过滤杂项 (商城入口13, GIF12, 推荐8, 动效推广等)
                if (!baseWhiteList.contains(info.type)) {
                    iterator.remove();
                }
            }

            // -----------------------------------------------------------------
            // 阶段 B: 计算目标插入位置并挂载 Telegram 贴纸
            // -----------------------------------------------------------------
            List<StickerPanel> panels = getPanels();
            if (panels.isEmpty()) return originalList;

            // 1. 优先寻找未被净化的第一个原生“商城大表情包 (type == 6)”，插在其正前方
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

            // 2. 若无商城大表情，则寻找“收藏表情 (type == 4)”，插在其后
            if (targetInsertIndex < 0) {
                for (int i = 0; i < originalList.size(); i++) {
                    Object itemObj = originalList.get(i);
                    if (itemObj instanceof EmotionPanelInfo) {
                        EmotionPanelInfo item = (EmotionPanelInfo) itemObj;
                        if (item.type == 4) {
                            targetInsertIndex = i + 1;
                            break;
                        }
                    }
                }
            }

            // 3. 若无收藏表情，则寻找“GIF动图 (type == 12)”，插在其后
            if (targetInsertIndex < 0) {
                for (int i = 0; i < originalList.size(); i++) {
                    Object itemObj = originalList.get(i);
                    if (itemObj instanceof EmotionPanelInfo) {
                        EmotionPanelInfo item = (EmotionPanelInfo) itemObj;
                        if (item.type == 12) {
                            targetInsertIndex = i + 1;
                            break;
                        }
                    }
                }
            }

            // 4. 若无上述项，保证插在末尾的“商城加号 (13) / 设置 (14)”之前
            if (targetInsertIndex < 0) {
                for (int i = 0; i < originalList.size(); i++) {
                    Object itemObj = originalList.get(i);
                    if (itemObj instanceof EmotionPanelInfo) {
                        EmotionPanelInfo item = (EmotionPanelInfo) itemObj;
                        if (item.type == 13 || item.type == 14 || item.type == 8) {
                            targetInsertIndex = i;
                            break;
                        }
                    }
                }
            }

            // 5. 兜底插入位置
            if (targetInsertIndex < 0) {
                targetInsertIndex = originalList.size();
            }

            // 查重并按序插入
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

                EmotionPanelInfo panelInfo = new EmotionPanelInfo(6, 4, panel.getEmoticonPackage());
                int insertPos = Math.min(targetInsertIndex + offset, originalList.size());
                originalList.add(insertPos, panelInfo);
                existingEpIds.add(epId);
                offset++;
            }

            PLog.d(TAG, "挂载 TG 贴纸完成 (净化: 商城=" + removeEmoticons + ", 杂项=" + removeMisc + ", 插入点=" + targetInsertIndex + ")");
        } catch (Throwable t) {
            PLog.e(TAG, "modifyPanelDataList 异常", t);
        }

        return originalList;
    }

    // =========================================================================
    // 静态插桩调用 2: 获取自定义表情包内的表情网格列表
    // =========================================================================
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

    // =========================================================================
    // 静态插桩调用 3: 判断是否为 TG 表情包 (防 handleIPSite 崩溃)
    // =========================================================================
    public static boolean isTgEmoticonPackage(Object pkgObj) {
        if (pkgObj instanceof EmoticonPackage) {
            EmoticonPackage pkg = (EmoticonPackage) pkgObj;
            return pkg.epId != null && pkg.epId.startsWith(EPID_PREFIX);
        }
        return false;
    }

    // =========================================================================
    // 静态插桩调用 4: 生成 Tab 栏图标 URL (返回 file:// 本地协议)
    // =========================================================================
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

    // =========================================================================
    // 静态插桩调用 5: 判断是否为 TG 表情 Info (转调 getZoomDrawable 防 OOM)
    // =========================================================================
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
    // 贴纸面板数据管理内部类
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
            this.pkg.status = 2; // 已就绪
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
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith(".") || name.endsWith(".nomedia") || name.endsWith(".txt.jpg")) continue;

                    int dotIdx = name.lastIndexOf(".");
                    if (dotIdx == -1) continue;
                    String ext = name.substring(dotIdx).toLowerCase();
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
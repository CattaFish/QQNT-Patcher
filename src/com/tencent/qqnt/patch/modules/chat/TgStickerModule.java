package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import android.view.View;

import com.tencent.mobileqq.data.EmoticonPackage;
import com.tencent.mobileqq.emoticonview.EmotionPanelData;
import com.tencent.mobileqq.emoticonview.EmotionPanelInfo;
import com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private static final Set<String> ALLOWED_EXTS = new HashSet<>(
            Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".webp")
    );

    private static final Map<String, StickerPanel> sPanelMap = new ConcurrentHashMap<>();
    private static volatile long sLastScanTime = 0L;
    private static final long SCAN_INTERVAL_MS = 5000L;

    @Override public String getId() { return "tg_stickers"; }
    @Override public String getName() { return "Telegram 表情包集"; }

    @Override public String getSubName() {
        if (!isEnabled()) {
            return "加载 /zzz/stickers/ 目录下的外部表情包";
        }
        int orphanCount = getOrphanImages().size();
        if (orphanCount > 0) {
            return "检测到 " + orphanCount + " 张图片未放入子文件夹，无法识别";
        }
        int count = sPanelMap.size();
        String summary = "已加载 " + count + " 个表情包 (" + getPanelColumns() + "列)";
        if (isRemoveQQEmoticons() || isRemoveQQMisc()) {
            summary += "，已开启净化";
        }
        return summary;
    }

    @Override public boolean defaultEnabled() { return false; }
    @Override public boolean hasConfig() { return true; }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 表情包配置", "配置", true, false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    public static List<File> getOrphanImages() {
        File baseDir = getStickerBaseDir();
        if (!baseDir.exists() || !baseDir.isDirectory()) return Collections.emptyList();
        File[] files = baseDir.listFiles();
        if (files == null) return Collections.emptyList();

        List<File> orphans = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                String name = f.getName().toLowerCase();
                int dotIdx = name.lastIndexOf(".");
                if (dotIdx != -1 && ALLOWED_EXTS.contains(name.substring(dotIdx))) {
                    orphans.add(f);
                }
            }
        }
        return orphans;
    }

    public static int autoCollectOrphanImages() {
        List<File> orphans = getOrphanImages();
        if (orphans.isEmpty()) return 0;

        File baseDir = getStickerBaseDir();
        File targetDir = new File(baseDir, "默认表情包");
        int suffix = 1;
        while (targetDir.exists() && !targetDir.isDirectory()) {
            targetDir = new File(baseDir, "默认表情包_" + suffix);
            suffix++;
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        int successCount = 0;
        for (File img : orphans) {
            File dest = new File(targetDir, img.getName());
            if (img.renameTo(dest)) {
                successCount++;
            }
        }
        return successCount;
    }

    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;

        List<StickerPanel> currentPanels = getPanels();
        final String stickerPath = getStickerBaseDir().getAbsolutePath();
        final List<File> orphanImages = getOrphanImages();

        final int[] tempColumns = new int[]{ getPanelColumns() };
        final boolean[] tempRemoveEmoticons = new boolean[]{ isRemoveQQEmoticons() };
        final boolean[] tempRemoveMisc = new boolean[]{ isRemoveQQMisc() };

        SettingDialogBuilder builder = SettingDialogBuilder.build(activity, "Telegram 表情包设置");

        builder.addTip("[存储路径] " + stickerPath + " (点击复制)\n"
                + "[目录规范] stickers/表情包名称/图片文件\n"
                + "[当前状态] 已识别 " + currentPanels.size() + " 个表情包", v -> {
            copyToClipboard(activity, stickerPath);
            ToastHelper.show(activity, "已复制路径");
        });

        if (!orphanImages.isEmpty()) {
            builder.addNoticeBanner(
                    "发现 " + orphanImages.size() + " 张图片未放入子文件夹",
                    "直接位于 stickers 根目录的图片无法被识别。每个表情包需要作为一个独立的子文件夹存放。",
                    "移动至「默认表情包」文件夹",
                    v -> {
                        int moved = autoCollectOrphanImages();
                        sLastScanTime = 0L;
                        sPanelMap.clear();
                        ToastHelper.show(activity, "已将 " + moved + " 张图片移动至「默认表情包」");
                        if (onSaved != null) onSaved.run();
                    }
            );
        }

        builder.addStepper("表情包显示列数", "每行排列表情数量", 3, 8, tempColumns[0], val -> tempColumns[0] = val)
                .addSwitch("移除商城表情", "隐藏官方商城下载的表情，仅保留外部贴纸", tempRemoveEmoticons[0], checked -> tempRemoveEmoticons[0] = checked)
                .addSwitch("移除杂项入口", "隐藏GIF热图、动效推广等杂项", tempRemoveMisc[0], checked -> tempRemoveMisc[0] = checked)
                .setPositiveButton("保存并更新", dialog -> {
                    setPanelColumns(tempColumns[0]);
                    setRemoveQQEmoticons(tempRemoveEmoticons[0]);
                    setRemoveQQMisc(tempRemoveMisc[0]);
                    sLastScanTime = 0L;
                    sPanelMap.clear();
                    dialog.dismiss();
                    ToastHelper.show(activity, "已保存配置 (" + tempColumns[0] + "列)");
                    if (onSaved != null) onSaved.run();
                })
                .show();
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("sticker_path", text));
            }
        } catch (Throwable ignored) {}
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

    public static boolean isRemoveQQEmoticons() { return ConfigManager.hasFlag(KEY_REMOVE_QQ_EMOTICONS); }
    public static void setRemoveQQEmoticons(boolean enable) { ConfigManager.setFlag(KEY_REMOVE_QQ_EMOTICONS, enable); }
    public static boolean isRemoveQQMisc() { return ConfigManager.hasFlag(KEY_REMOVE_QQ_MISC); }
    public static void setRemoveQQMisc(boolean enable) { ConfigManager.setFlag(KEY_REMOVE_QQ_MISC, enable); }

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
        return new File(zzzDir, "stickers");
    }

    private static void ensureStickerDir() {
        try {
            File baseDir = getStickerBaseDir();
            if (!baseDir.exists()) baseDir.mkdirs();

            File noMedia = new File(baseDir, ".nomedia");
            if (!noMedia.exists()) noMedia.createNewFile();

            File sampleDir = new File(baseDir, "示例表情包");
            File[] subs = baseDir.listFiles(File::isDirectory);
            if (subs == null || subs.length == 0) {
                sampleDir.mkdirs();
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List modifyPanelDataList(List originalList) {
        if (!ConfigManager.isModuleEnabled("tg_stickers", false) || originalList == null || originalList.isEmpty()) {
            return originalList;
        }

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
                if (n1 != n2) return Long.compare(n1, n2);
            } else {
                char l1 = Character.toLowerCase(c1);
                char l2 = Character.toLowerCase(c2);
                if (l1 != l2) return l1 - l2;
                i++;
                j++;
            }
        }
        return len1 - len2;
    }

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

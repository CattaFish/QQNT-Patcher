package com.tencent.qqnt.patch.modules.chat;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShowDownloadTimesModule implements IPatchModule {

    private static final String TAG = "GroupFile";
    private static final Map<String, Integer> sCountMap = Collections.synchronizedMap(
            new LinkedHashMap<String, Integer>(200, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > 3000;
                }
            }
    );

    @Override public String getId() { return "show_file_download_count"; }
    @Override public String getName() { return "群文件显示下载次数"; }
    @Override public boolean defaultEnabled() { return false; }

    public static void handleGroupFileListResponse(Object responseObj) {
        if (responseObj == null) return;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", false)) return;

        try {
            List<?> cList = findListFromObject(responseObj);
            if (cList == null || cList.isEmpty()) return;

            int countAdded = 0;
            for (Object cObj : cList) {
                if (cObj == null) continue;

                for (Field cf : cObj.getClass().getFields()) {
                    Object aObj = cf.get(cObj);
                    if (aObj == null) continue;

                    String fileId = null;
                    Integer count = null;

                    try {
                        Method dMethod = aObj.getClass().getMethod("d");
                        Map<?, ?> pbMap = (Map<?, ?>) dMethod.invoke(aObj);
                        if (pbMap != null) {
                            Object tag1 = pbMap.get(1);
                            Object tag9 = pbMap.get(9);
                            if (tag1 != null) fileId = (String) getPbValueSafe(tag1);
                            if (tag9 != null) {
                                Object cntVal = getPbValueSafe(tag9);
                                if (cntVal instanceof Number) count = ((Number) cntVal).intValue();
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (fileId == null) {
                        try {
                            Field dF = aObj.getClass().getField("d");
                            Object idVal = dF.get(aObj);
                            if (idVal != null) fileId = idVal.toString();
                        } catch (Throwable ignored) {}
                    }
                    if (count == null) {
                        try {
                            Field pF = aObj.getClass().getField("p");
                            Object cntVal = pF.get(aObj);
                            if (cntVal instanceof Number) count = ((Number) cntVal).intValue();
                        } catch (Throwable ignored) {
                            try {
                                Field eF = aObj.getClass().getField("E");
                                Object cntVal = eF.get(aObj);
                                if (cntVal instanceof Number) count = ((Number) cntVal).intValue();
                            } catch (Throwable ignored2) {}
                        }
                    }

                    if (fileId != null && !fileId.isEmpty()) {
                        String cleanId = fileId.replaceAll("^/+", "").trim();
                        int times = (count != null) ? count : 0;
                        sCountMap.put(cleanId, times);
                        sCountMap.put(cleanId.toLowerCase(), times);
                        countAdded++;
                    }
                }
            }
            PLog.i(TAG, "成功捕获群文件记录 " + countAdded + " 条，当前字典缓存总数=" + sCountMap.size());
        } catch (Throwable t) {
            PLog.e(TAG, "解析群文件回包异常", t);
        }
    }

    public static void handleGroupFileList(Object fileListObj, Object responseObj) {
        handleGroupFileListResponse(responseObj);
    }

    public static String appendDownloadCountToStatusText(String originalStatus, Object fileItemObj) {
        if (!ConfigManager.isModuleEnabled("show_file_download_count", false)) return originalStatus;

        try {
            String fileId = extractFileId(fileItemObj);
            if (fileId == null || fileId.isEmpty()) return originalStatus;

            Integer count = sCountMap.get(fileId);
            if (count == null) count = sCountMap.get(fileId.toLowerCase());

            if (count == null) {
                return originalStatus;
            }

            if (originalStatus != null && originalStatus.endsWith("次")) return originalStatus;

            return originalStatus + " · " + count + " 次";
        } catch (Throwable t) {
            PLog.e(TAG, "追加群文件下载次数异常", t);
        }
        return originalStatus;
    }

    private static String extractFileId(Object obj) {
        if (obj == null) return null;

        try {
            Field dField = obj.getClass().getDeclaredField("d");
            dField.setAccessible(true);
            Object inner = dField.get(obj);
            if (inner != null) {
                Field fIdField = inner.getClass().getDeclaredField("d");
                fIdField.setAccessible(true);
                Object id = fIdField.get(inner);
                if (id != null) {
                    return id.toString().replaceAll("^/+", "").trim();
                }
            }
        } catch (Throwable ignored) {}

        try {
            Method m = obj.getClass().getMethod("getFileId");
            Object id = m.invoke(obj);
            if (id != null) return id.toString().replaceAll("^/+", "").trim();
        } catch (Throwable ignored) {}

        try {
            Field aF = obj.getClass().getDeclaredField("a");
            aF.setAccessible(true);
            Object id = aF.get(obj);
            if (id != null && id.toString().length() > 5) {
                return id.toString().replaceAll("^/+", "").trim();
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static List<?> findListFromObject(Object obj) {
        if (obj == null) return null;
        for (Field f : obj.getClass().getFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                try { return (List<?>) f.get(obj); } catch (Throwable ignored) {}
            }
        }
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                try { f.setAccessible(true); return (List<?>) f.get(obj); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object getPbValueSafe(Object entry) {
        if (entry == null) return null;
        try {
            Method m = entry.getClass().getMethod("getValue");
            return m.invoke(entry);
        } catch (Throwable ignored) {}
        try {
            Field f = entry.getClass().getDeclaredField("value");
            f.setAccessible(true);
            return f.get(entry);
        } catch (Throwable ignored) {}
        return null;
    }

    public static void handleTroopFileGetView(View view, Object adapter, int position) {
        if (view == null || adapter == null) return;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", false)) return;
        try {
            if (!(view instanceof ViewGroup)) return;
            Class<?> adapterClz = adapter.getClass();
            Method getItemM = adapterClz.getMethod("getItem", int.class);
            Object fileInfo = getItemM.invoke(adapter, position);
            if (fileInfo == null) return;

            String infoStr = fileInfo.toString();
            Matcher m = Pattern.compile("uint32_download_times=(\\d+)").matcher(infoStr);
            if (m.find()) {
                String countStr = m.group(1);
                appendCountToTextView((ViewGroup) view, fileInfo, countStr);
            }
        } catch (Throwable ignored) {}
    }

    private static void appendCountToTextView(ViewGroup vg, Object fileInfo, String countStr) {
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                if (tv.getTag() == fileInfo) {
                    CharSequence cs = tv.getText();
                    if (cs != null && !cs.toString().endsWith("次")) {
                        tv.setText(cs + " · " + countStr + " 次");
                    }
                    return;
                }
            } else if (child instanceof ViewGroup) {
                appendCountToTextView((ViewGroup) child, fileInfo, countStr);
            }
        }
    }
}

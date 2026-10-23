package com.tencent.qqnt.patch.modules.chat;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
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

    public static void handleGroupFileList(Object fileListObj, Object responseObj) {
        handleGroupFileListResponse(responseObj);
    }

    public static void handleGroupFileListResponse(Object responseObj) {
        if (responseObj == null) return;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", false)) return;

        try {
            List<?> cList = findListFromObject(responseObj);
            if (cList == null || cList.isEmpty()) return;

            int countAdded = 0;
            for (Object cObj : cList) {
                if (cObj == null) continue;

                // 尝试从单项或其子对象中全动态抽取 (fileId, downloadTimes)
                Pair<String, Integer> pair = dynamicExtractFileInfo(cObj);
                if (pair != null && pair.first != null && !pair.first.isEmpty()) {
                    String cleanId = pair.first.replaceAll("^/+", "").trim();
                    int times = (pair.second != null) ? pair.second : 0;
                    sCountMap.put(cleanId, times);
                    sCountMap.put(cleanId.toLowerCase(), times);
                    countAdded++;
                }
            }
            PLog.i(TAG, "成功捕获群文件记录 " + countAdded + " 条，当前字典缓存总数=" + sCountMap.size());
        } catch (Throwable t) {
            PLog.e(TAG, "解析群文件回包异常", t);
        }
    }

    /**
     * 全动态嗅探单个文件项：完全脱离方法名与混淆字段名
     */
    private static Pair<String, Integer> dynamicExtractFileInfo(Object obj) {
        if (obj == null) return null;

        // 1. 先探测当前对象自身
        Pair<String, Integer> res = inspectObjectForPbTags(obj);
        if (res != null) return res;

        // 2. 深度优先探测一层子对象 (如 cObj.h)
        for (Field f : getAllDeclaredFields(obj.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                Object child = f.get(obj);
                if (child != null && child != obj) {
                    res = inspectObjectForPbTags(child);
                    if (res != null) return res;
                }
            } catch (Throwable ignored) {}
        }

        // 3. 泛用特征兜底：直接查找文件 ID 字符串与整数字段
        return fallbackInspectByFieldTypes(obj);
    }

    /**
     * 嗅探 Protobuf 字典（Tag 1 = fileId, Tag 9 = downloadTimes）
     * 无论生成方法名叫 d()、e() 还是 getMap()，通杀所有版本
     */
    private static Pair<String, Integer> inspectObjectForPbTags(Object target) {
        if (target == null) return null;
        Class<?> clz = target.getClass();

        // 动态寻找任何“无参且返回 Map”的公有/私有方法
        for (Method m : clz.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && Map.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) m.invoke(target);
                    if (map != null && (map.containsKey(1) || map.containsKey(1L))) {
                        Object tag1 = map.containsKey(1) ? map.get(1) : map.get(1L);
                        Object tag9 = map.containsKey(9) ? map.get(9) : map.get(9L);

                        String fileId = extractStringValue(tag1);
                        Integer count = extractIntValue(tag9);

                        if (fileId != null && !fileId.isEmpty()) {
                            return new Pair<>(fileId, count != null ? count : 0);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * 类型泛化解包：自动解包 pbandk.h、PBStringField、PBUInt32Field、String、Integer 等所有包装类
     */
    private static String extractStringValue(Object obj) {
        if (obj == null) return null;
        if (obj instanceof CharSequence) return obj.toString();

        Object val = unwrapPbValue(obj);
        if (val instanceof CharSequence) return val.toString();

        for (Field f : getAllDeclaredFields(obj.getClass())) {
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof CharSequence) {
                    String s = v.toString();
                    if (s.startsWith("/") || s.length() >= 16) return s;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Integer extractIntValue(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();

        Object val = unwrapPbValue(obj);
        if (val instanceof Number) return ((Number) val).intValue();

        for (Field f : getAllDeclaredFields(obj.getClass())) {
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static Object unwrapPbValue(Object obj) {
        if (obj == null) return null;
        try {
            Method m = obj.getClass().getMethod("get");
            return m.invoke(obj);
        } catch (Throwable ignored) {}
        try {
            Method m = obj.getClass().getMethod("getValue");
            return m.invoke(obj);
        } catch (Throwable ignored) {}
        try {
            Field f = obj.getClass().getDeclaredField("value");
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {}
        return obj;
    }

    private static Pair<String, Integer> fallbackInspectByFieldTypes(Object obj) {
        String fileId = null;
        Integer count = null;

        for (Field f : getAllDeclaredFields(obj.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof String && fileId == null) {
                    String str = (String) v;
                    if (str.startsWith("/") && str.length() > 5) {
                        fileId = str;
                    }
                } else if (v instanceof Number && count == null) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("download") || name.contains("count") || name.contains("times") || name.equals("q") || name.equals("p")) {
                        count = ((Number) v).intValue();
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (fileId != null) {
            return new Pair<>(fileId, count != null ? count : 0);
        }
        return null;
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

    /**
     * UI 端动态提取 fileId：不再硬编码 "d" 或 "a"
     */
    private static String extractFileId(Object obj) {
        if (obj == null) return null;

        // 1. 优先调用标准方法 getFileId()
        try {
            Method m = obj.getClass().getMethod("getFileId");
            Object id = m.invoke(obj);
            if (id != null && !id.toString().isEmpty()) {
                return id.toString().replaceAll("^/+", "").trim();
            }
        } catch (Throwable ignored) {}

        // 2. 深度反射遍历所有属性，找到以 "/" 开头的特征文件 ID
        return searchFileIdRecursively(obj, 0);
    }

    private static String searchFileIdRecursively(Object obj, int depth) {
        if (obj == null || depth > 2) return null;

        for (Field f : getAllDeclaredFields(obj.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof String) {
                    String s = (String) val;
                    if (s.startsWith("/") && s.length() > 5) {
                        return s.replaceAll("^/+", "").trim();
                    }
                } else if (val != null && !f.getType().isPrimitive() && !f.getType().getName().startsWith("android.")) {
                    String sub = searchFileIdRecursively(val, depth + 1);
                    if (sub != null) return sub;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<?> findListFromObject(Object obj) {
        if (obj == null) return null;
        for (Field f : getAllDeclaredFields(obj.getClass())) {
            if (List.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    return (List<?>) f.get(obj);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static List<Field> getAllDeclaredFields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            Collections.addAll(list, curr.getDeclaredFields());
            curr = curr.getSuperclass();
        }
        return list;
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

    private static class Pair<A, B> {
        final A first;
        final B second;
        Pair(A first, B second) { this.first = first; this.second = second; }
    }
}
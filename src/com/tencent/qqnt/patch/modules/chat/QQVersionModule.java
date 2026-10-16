package com.tencent.qqnt.patch.modules.chat;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.PatchAssetHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class QQVersionModule implements IPatchModule {

    private static final String TAG = "QqVersion";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    public static final String UNKNOWN_STATUS = "未捕获到客户端版本";

    private static final String[] REMOTE_SUBID_URLS = new String[]{
            "https://ghfast.top/https://raw.githubusercontent.com/Cattafish/qqDecoder/main/data/subid.json",
            "https://cdn.jsdelivr.net/gh/Cattafish/qqDecoder@main/data/subid.json",
            "https://raw.githubusercontent.com/Cattafish/qqDecoder/main/data/subid.json"
    };

    public static class SubidDetail {
        public String subId;
        public String summary;
        public String platform;
        public String arch;
        public String version;
        public String date;
        public List<String> packages = new ArrayList<>();
        public List<String> versions = new ArrayList<>();
    }

    private static final Map<String, Long> sLastSubidByUin = new ConcurrentHashMap<>();
    private static final Map<String, String> sExactTable = new ConcurrentHashMap<>();
    private static final Map<String, SubidDetail> sDetailTable = new ConcurrentHashMap<>();
    private static final Map<String, String> sFuzzyTable = new ConcurrentHashMap<>();

    private static final AtomicBoolean sDictLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean sOtaChecked = new AtomicBoolean(false);
    private static final Handler sDebounceHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sSaveRunnable = () -> new Thread(QQVersionModule::doSaveSubidCache).start();

    @Override public String getId() { return "qq_version"; }
    @Override public String getName() { return "发送者 QQ 版本识别"; }
    @Override public boolean defaultEnabled() { return false; }

    @Override
    public String getSubName() {
        if (!isEnabled()) {
            return "在好友设置与群成员设置展示对方版本与架构详情";
        }
        return "在好友设置与群成员设置展示对方版本与架构 (已捕获 " + sLastSubidByUin.size() + " 人)";
    }

    @Override
    public void onInit(Context context) {
        ensureDictionaryLoaded(context);
        loadLocalSubidCache(context);
        triggerSilentOtaSync(context);
    }

    @Override
    public byte[] onMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) {
        if (!isEnabled() || buf == null || !CMD_MSG_PUSH.equals(cmd)) {
            return buf;
        }

        try {
            byte[] body = (buf.length >= 4 && (buf[0] & 0xFF) == 0) ? subArray(buf, 4) : buf;
            byte[] qqMsg = getProtoBytes(body, 1);
            if (qqMsg == null) return buf;

            byte[] head = getProtoBytes(qqMsg, 1);
            if (head == null) return buf;

            long uin = getProtoVarint(head, 1);
            long subId = getProtoVarint(head, 4);

            if (subId > 0 && uin > 10000L) {
                sLastSubidByUin.put(String.valueOf(uin), subId);
                scheduleDebounceSave();
            }
        } catch (Throwable t) {
            PLog.d(TAG, "解析 MsgPush subid 异常: " + t.getMessage());
        }

        return buf;
    }

    public static long getSubidByUin(String uin) {
        if (uin == null || uin.isEmpty()) return 0L;
        Long subId = sLastSubidByUin.get(uin);
        return subId != null ? subId : 0L;
    }

    public static String getVersionByUin(String uin) {
        if (uin == null || uin.isEmpty()) return "未知用户";
        long subId = getSubidByUin(uin);
        if (subId <= 0) return UNKNOWN_STATUS;
        return lookupVersion(subId);
    }

    public static String lookupVersion(long subId) {
        String sv = String.valueOf(subId);
        String hit = sExactTable.get(sv);
        if (hit != null && !hit.isEmpty()) return hit;

        if (sv.length() >= 6) {
            String mid = sv.substring(2, 6);
            String fuzzyHit = sFuzzyTable.get(mid);
            if (fuzzyHit != null && !fuzzyHit.isEmpty()) return fuzzyHit + "~";
        }
        return "未知版本 (" + subId + ")";
    }

    public static SubidDetail lookupDetail(long subId) {
        return sDetailTable.get(String.valueOf(subId));
    }

    private static synchronized void ensureDictionaryLoaded(Context context) {
        if (sDictLoaded.getAndSet(true)) return;

        loadBuiltinFallback();

        String[] dictNames = new String[]{"builtin_subid.json", "tim_subid.json", "win_subid.json", "subid.json"};
        for (String name : dictNames) {
            try (InputStream is = PatchAssetHelper.openStream(context, name)) {
                if (is != null) {
                    parseJsonDictionary(is);
                }
            } catch (Throwable ignored) {}
        }

        File extDictFile = getExternalDictionaryFile(context);
        if (extDictFile != null && extDictFile.exists() && extDictFile.isFile()) {
            try (InputStream fis = new FileInputStream(extDictFile)) {
                parseJsonDictionary(fis);
            } catch (Throwable ignored) {}
        }

        PLog.i(TAG, "版本字典底表加载完成，当前收录: " + sExactTable.size() + " 条");
    }

    private static void triggerSilentOtaSync(Context context) {
        if (sOtaChecked.getAndSet(true)) return;

        new Thread(() -> {
            try {
                Thread.sleep(6000);

                String remoteJsonStr = null;
                for (String urlStr : REMOTE_SUBID_URLS) {
                    try {
                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client");

                        if (conn.getResponseCode() == 200) {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) sb.append(line);
                                remoteJsonStr = sb.toString();
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                if (remoteJsonStr == null || remoteJsonStr.trim().isEmpty()) return;

                JSONObject remoteJson = new JSONObject(remoteJsonStr);
                File extFile = getExternalDictionaryFile(context);
                if (extFile == null) return;

                JSONObject localJson = new JSONObject();
                if (extFile.exists()) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(extFile), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) sb.append(l);
                        localJson = new JSONObject(sb.toString());
                    } catch (Throwable ignored) {}
                }

                int newCount = 0;
                Iterator<String> it = remoteJson.keys();
                while (it.hasNext()) {
                    String subid = it.next();
                    Object rVal = remoteJson.opt(subid);

                    if (!localJson.has(subid) || !sExactTable.containsKey(subid)) {
                        localJson.put(subid, rVal);
                        newCount++;
                    } else {
                        Object lVal = localJson.opt(subid);
                        if (lVal instanceof String && rVal instanceof JSONObject) {
                            localJson.put(subid, rVal);
                            newCount++;
                        }
                    }
                }

                if (newCount > 0) {
                    try (FileOutputStream fos = new FileOutputStream(extFile);
                         OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                        osw.write(localJson.toString(2));
                        osw.flush();
                    }
                    try (InputStream is = new FileInputStream(extFile)) {
                        parseJsonDictionary(is);
                    }
                    PLog.i(TAG, "已成功完成 SubID 字典静默增量更新，吸收新版本: " + newCount + " 个，当前总数: " + sExactTable.size());
                }
            } catch (Throwable t) {
                PLog.d(TAG, "静默 OTA 增量同步跳过: " + t.getMessage());
            }
        }).start();
    }

    private static void parseJsonDictionary(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        JSONObject root = new JSONObject(sb.toString());

        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("subid_map".equals(key) || "last_subid".equals(key)) continue;

            Object valObj = root.opt(key);
            if (valObj == null) continue;

            SubidDetail detail = new SubidDetail();
            detail.subId = key;

            if (valObj instanceof JSONObject) {
                JSONObject obj = (JSONObject) valObj;
                detail.summary = obj.optString("summary", "");
                detail.platform = obj.optString("platform", "");
                detail.arch = obj.optString("arch", "");
                detail.version = obj.optString("version", "");
                detail.date = obj.optString("date", "");

                JSONArray pkgArr = obj.optJSONArray("packages");
                if (pkgArr != null) {
                    for (int i = 0; i < pkgArr.length(); i++) detail.packages.add(pkgArr.getString(i));
                }
                JSONArray verArr = obj.optJSONArray("versions");
                if (verArr != null) {
                    for (int i = 0; i < verArr.length(); i++) detail.versions.add(verArr.getString(i));
                }

                if (detail.summary.isEmpty()) detail.summary = detail.version;
            } else if (valObj instanceof String) {
                String str = ((String) valObj).trim();
                detail.summary = str;
                detail.version = str;
            } else continue;

            sExactTable.put(key, detail.summary);
            sDetailTable.put(key, detail);

            if (key.length() >= 6) {
                String mid = key.substring(2, 6);
                if (!sFuzzyTable.containsKey(mid) || detail.summary.contains("(")) {
                    sFuzzyTable.put(mid, detail.summary);
                }
            }
        }
    }

    private static void loadBuiltinFallback() {
        sExactTable.put("537282233", "QQ(Watch) 9.0.7");
        sExactTable.put("537246140", "QQ(Linux) 3.2.12");
        sExactTable.put("537246115", "QQ(Mac) 6.9.55");
        sExactTable.put("537246092", "QQ(Win) 9.9.15");
        sExactTable.put("537395444", "QQ 9.3.70");
    }

    private static void loadLocalSubidCache(Context context) {
        File f = getCacheFile(context);
        if (f == null || !f.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            JSONObject obj = new JSONObject(sb.toString());
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String uin = it.next();
                long sub = obj.optLong(uin, 0L);
                if (sub > 0) sLastSubidByUin.put(uin, sub);
            }
        } catch (Throwable ignored) {}
    }

    private static void scheduleDebounceSave() {
        sDebounceHandler.removeCallbacks(sSaveRunnable);
        sDebounceHandler.postDelayed(sSaveRunnable, 3000L);
    }

    private static void doSaveSubidCache() {
        try {
            Context ctx = AppContext.get();
            if (ctx == null) return;
            File f = getCacheFile(ctx);
            if (f == null) return;

            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Long> entry : sLastSubidByUin.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }

            try (FileOutputStream fos = new FileOutputStream(f);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(obj.toString());
                osw.flush();
            }
        } catch (Throwable ignored) {}
    }

    private static File getZzzDir(Context context) {
        File dir = null;
        try {
            File[] mDirs = context.getExternalMediaDirs();
            if (mDirs != null && mDirs.length > 0 && mDirs[0] != null) dir = mDirs[0];
        } catch (Throwable ignored) {}
        if (dir == null) dir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + context.getPackageName());
        File zzz = new File(dir, "zzz");
        if (!zzz.exists()) zzz.mkdirs();
        return zzz;
    }

    private static File getCacheFile(Context context) { return new File(getZzzDir(context), "subid_cache.json"); }
    private static File getExternalDictionaryFile(Context context) { return new File(getZzzDir(context), "subid_dict.json"); }

    private static byte[] subArray(byte[] src, int start) {
        if (src == null || start >= src.length) return new byte[0];
        byte[] d = new byte[src.length - start];
        System.arraycopy(src, start, d, 0, d.length);
        return d;
    }

    private static byte[] getProtoBytes(byte[] data, int targetField) {
        if (data == null) return null;
        int pos = 0, limit = data.length;
        while (pos < limit) {
            long[] tagRes = readVarint(data, pos, limit);
            if (tagRes == null) break;
            int fn = (int) (tagRes[0] >>> 3), wt = (int) (tagRes[0] & 7);
            pos = (int) tagRes[1];
            if (wt == 2) {
                long[] lenRes = readVarint(data, pos, limit);
                if (lenRes == null) break;
                int len = (int) lenRes[0];
                pos = (int) lenRes[1];
                if (pos + len > limit) break;
                if (fn == targetField) {
                    byte[] b = new byte[len];
                    System.arraycopy(data, pos, b, 0, len);
                    return b;
                }
                pos += len;
            } else if (wt == 0) {
                long[] vRes = readVarint(data, pos, limit);
                if (vRes == null) break;
                pos = (int) vRes[1];
            } else if (wt == 1) pos += 8;
            else if (wt == 5) pos += 4;
            else break;
        }
        return null;
    }

    private static long getProtoVarint(byte[] data, int targetField) {
        if (data == null) return 0L;
        int pos = 0, limit = data.length;
        while (pos < limit) {
            long[] tagRes = readVarint(data, pos, limit);
            if (tagRes == null) break;
            int fn = (int) (tagRes[0] >>> 3), wt = (int) (tagRes[0] & 7);
            pos = (int) tagRes[1];
            if (wt == 0) {
                long[] vRes = readVarint(data, pos, limit);
                if (vRes == null) break;
                pos = (int) vRes[1];
                if (fn == targetField) return vRes[0];
            } else if (wt == 2) {
                long[] lenRes = readVarint(data, pos, limit);
                if (lenRes == null) break;
                pos = (int) lenRes[1] + (int) lenRes[0];
            } else if (wt == 1) pos += 8;
            else if (wt == 5) pos += 4;
            else break;
        }
        return 0L;
    }

    private static long readVarint(byte[] data, int pos, int limit) {
        long result = 0;
        for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return new long[]{result, pos};
        }
        return null;
    }
}

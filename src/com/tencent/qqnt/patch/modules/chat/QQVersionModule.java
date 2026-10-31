package com.tencent.qqnt.patch.modules.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.PatchAssetHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class QQVersionModule implements IPatchModule {

    private static final String TAG = "QQVersion";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    public static final String UNKNOWN_STATUS = "未捕获到客户端版本";

    private static final String FALLBACK_PROXY_URL = "https://gh-proxy.com/https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json";

    private static final String PREF_NAME = "zzz_subid_ota_pref";
    private static final String KEY_ETAG = "subid_etag";

    public static class SubidDetail {
        public String subId;
        public String summary;
        public String platform;
        public String arch;
        public String version;
        public String date;
        public String channel;
        public Boolean isBeta = null;
        public List<String> packages = new ArrayList<>();
        public List<String> versions = new ArrayList<>();
        public Map<String, String> extraFields = new LinkedHashMap<>();
    }

    public static class InferResult {
        public boolean isExact = false;
        public Boolean isBeta = null;
        public String displaySummary;
        public SubidDetail exactDetail;
        public SubidDetail floorDetail;
        public SubidDetail ceilDetail;
        public long floorSubid = 0;
        public long ceilSubid = 0;
        public String suspectedPlatform = "";
        public String suspectedArch = "";
        public String suspectedVersion = "";
        public String warningNote = "";
    }

    public static class UserDeviceRecord {
        public long subId;
        public long timestamp;

        public UserDeviceRecord(long subId, long timestamp) {
            this.subId = subId;
            this.timestamp = timestamp;
        }
    }

    private static final Map<String, List<UserDeviceRecord>> sUserRecords = new ConcurrentHashMap<>();

    private static final Map<String, String> sExactTable = new ConcurrentHashMap<>();
    private static final Map<String, SubidDetail> sDetailTable = new ConcurrentHashMap<>();
    private static final ConcurrentSkipListMap<Long, SubidDetail> sNavMap = new ConcurrentSkipListMap<>();

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
            return "在好友设置与群成员设置展示对方版本与架构";
        }
        return "在好友设置与群成员设置展示对方版本与架构\n" +
               "(已捕获 " + sUserRecords.size() + " 人)";
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
                recordSubid(String.valueOf(uin), subId, System.currentTimeMillis());
                scheduleDebounceSave();
            }
        } catch (Throwable t) {
            PLog.d(TAG, "解析 MsgPush subid 异常: " + t.getMessage());
        }

        return buf;
    }

    private static void recordSubid(String uin, long subId, long timestamp) {
        List<UserDeviceRecord> list = sUserRecords.computeIfAbsent(uin, k -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            if (list.isEmpty()) {
                list.add(new UserDeviceRecord(subId, timestamp));
                return;
            }

            UserDeviceRecord latest = list.get(0);
            if (latest.subId == subId) {
                latest.timestamp = timestamp;
                return;
            }

            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).subId == subId) {
                    list.remove(i);
                    break;
                }
            }

            // 不限制历史记录数量，真实完整追加变动轨迹
            list.add(0, new UserDeviceRecord(subId, timestamp));
        }
    }

    public static List<UserDeviceRecord> getUserRecords(String uin) {
        if (uin == null || uin.isEmpty()) return Collections.emptyList();
        List<UserDeviceRecord> list = sUserRecords.get(uin);
        return list != null ? list : Collections.emptyList();
    }

    public static long getSubidByUin(String uin) {
        List<UserDeviceRecord> records = getUserRecords(uin);
        return (!records.isEmpty()) ? records.get(0).subId : 0L;
    }

    public static String getVersionByUin(String uin) {
        if (uin == null || uin.isEmpty()) return "未知用户";
        long subId = getSubidByUin(uin);
        if (subId <= 0) return UNKNOWN_STATUS;
        return inferSubid(subId).displaySummary;
    }

    public static String formatRelativeTime(long timeMs) {
        if (timeMs <= 0) return "未知时间";
        long now = System.currentTimeMillis();
        long diff = now - timeMs;
        if (diff < 0) diff = 0;

        if (diff < 60 * 1000L) {
            return "刚刚";
        } else if (diff < 60 * 60 * 1000L) {
            return (diff / (60 * 1000L)) + " 分钟前";
        } else if (diff < 24 * 60 * 60 * 1000L) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            return "今天 " + sdf.format(new Date(timeMs));
        } else if (diff < 48 * 60 * 60 * 1000L) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            return "昨天 " + sdf.format(new Date(timeMs));
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(timeMs));
        }
    }

    public static InferResult inferSubid(long subId) {
        InferResult res = new InferResult();
        String sv = String.valueOf(subId);

        SubidDetail exact = sDetailTable.get(sv);
        if (exact != null) {
            res.isExact = true;
            res.isBeta = exact.isBeta;
            res.exactDetail = exact;
            res.displaySummary = (exact.summary != null && !exact.summary.isEmpty()) ? exact.summary : exact.version;
            return res;
        }

        Map.Entry<Long, SubidDetail> floor = sNavMap.floorEntry(subId);
        Map.Entry<Long, SubidDetail> ceil = sNavMap.ceilingEntry(subId);

        if (floor != null) {
            res.floorSubid = floor.getKey();
            res.floorDetail = floor.getValue();
        }
        if (ceil != null) {
            res.ceilSubid = ceil.getKey();
            res.ceilDetail = ceil.getValue();
        }

        if (ceil == null && floor != null) {
            long diff = subId - floor.getKey();
            res.isBeta = floor.getValue().isBeta;
            res.suspectedPlatform = floor.getValue().platform;
            res.suspectedArch = floor.getValue().arch;
            res.suspectedVersion = floor.getValue().version;
            res.warningNote = "此 SubID (" + subId + ") 高于本地收录最高版本，推测为近期发布的更新版本，可能不准确";
            if (diff < 10000) {
                res.displaySummary = "未精确匹配 (推测为近期新版?)";
            } else {
                res.displaySummary = "未收录版本 (" + subId + ")";
            }
            return res;
        }

        if (floor != null && ceil != null) {
            SubidDetail fDet = floor.getValue();
            SubidDetail cDet = ceil.getValue();
            long dFloor = subId - floor.getKey();
            long dCeil = ceil.getKey() - subId;

            SubidDetail ref = (dFloor <= dCeil) ? fDet : cDet;
            res.isBeta = ref.isBeta;
            res.suspectedPlatform = ref.platform;
            res.suspectedArch = ref.arch;

            String vRef = ref.version != null ? ref.version : "";
            String[] parts = vRef.split("\\.");
            String guessVer = (parts.length >= 3) ? (parts[0] + "." + parts[1] + "." + parts[2]) : vRef;
            res.suspectedVersion = guessVer;

            String platArch = ref.platform != null ? ref.platform : "";
            if (ref.arch != null && !ref.arch.isEmpty()) platArch += "-" + ref.arch;

            res.warningNote = "未精确收录此 SubID，根据临近发版区间推测可能为 " + platArch + " " + guessVer + "，推测仅供参考";
            res.displaySummary = "未匹配 (推测: " + platArch + " " + guessVer + "?)";
            return res;
        }

        if (ceil != null) {
            res.isBeta = ceil.getValue().isBeta;
            res.suspectedPlatform = ceil.getValue().platform;
            res.suspectedArch = ceil.getValue().arch;
            res.suspectedVersion = ceil.getValue().version;
            res.warningNote = "此 SubID 较低，推测为历史未收录老版本，可能不准确";
            res.displaySummary = "未匹配 (推测为历史老版?)";
            return res;
        }

        res.displaySummary = "未匹配版本 (" + subId + ")";
        res.warningNote = "完全未知号段，无法进行有效区间推测";
        return res;
    }

    private static synchronized void ensureDictionaryLoaded(Context context) {
        if (sDictLoaded.getAndSet(true)) return;

        loadBuiltinFallback();

        File extDictFile = getExternalDictionaryFile(context);
        if (extDictFile != null && extDictFile.exists() && extDictFile.isFile()) {
            try (InputStream fis = new FileInputStream(extDictFile)) {
                parseJsonDictionary(fis);
            } catch (Throwable ignored) {}
        }

        String[] dictNames = new String[]{"builtin_subid.json", "tim_subid.json", "win_subid.json", "subid.json"};
        for (String name : dictNames) {
            try (InputStream is = PatchAssetHelper.openStream(context, name)) {
                if (is != null) {
                    parseJsonDictionary(is);
                }
            } catch (Throwable ignored) {}
        }

        PLog.i(TAG, "版本字典底表加载完成，当前收录: " + sExactTable.size() + " 条");
    }

    private static void triggerSilentOtaSync(Context context) {
        if (sOtaChecked.getAndSet(true)) return;

        new Thread(() -> {
            try {
                Thread.sleep(2000);

                File extFile = getExternalDictionaryFile(context);
                if (extFile == null) return;

                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String recordedEtag = sp.getString(KEY_ETAG, "");

                String primaryUrl = ConfigManager.UPDATE_API_URL + "/subid.json";
                String[] candidateUrls = new String[]{ primaryUrl, FALLBACK_PROXY_URL };

                for (String endpoint : candidateUrls) {
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(endpoint);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(6000);
                        conn.setReadTimeout(8000);
                        conn.setInstanceFollowRedirects(true);
                        conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client/" + ConfigManager.VERSION);

                        if (endpoint.equals(primaryUrl) && extFile.exists() && extFile.length() > 0 && !recordedEtag.isEmpty()) {
                            conn.setRequestProperty("If-None-Match", recordedEtag);
                        }

                        int code = conn.getResponseCode();

                        if (code == 304) {
                            PLog.i(TAG, "SubID 字典通过 ETag 校验：云端数据未变动 (304 Not Modified)");
                            return;
                        }

                        if (code == 200) {
                            String newEtag = conn.getHeaderField("ETag");
                            File tmpFile = new File(extFile.getAbsolutePath() + ".tmp");

                            try (InputStream is = conn.getInputStream();
                                 FileOutputStream fos = new FileOutputStream(tmpFile)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = is.read(buf)) != -1) {
                                    fos.write(buf, 0, len);
                                }
                                fos.flush();
                                fos.getFD().sync();
                            }

                            if (tmpFile.exists() && tmpFile.length() > 0) {
                                if (extFile.exists()) extFile.delete();
                                tmpFile.renameTo(extFile);

                                if (newEtag != null && !newEtag.isEmpty()) {
                                    sp.edit().putString(KEY_ETAG, newEtag).apply();
                                }

                                try (InputStream fis = new FileInputStream(extFile)) {
                                    parseJsonDictionary(fis);
                                }
                                PLog.i(TAG, "SubID 字典云端实时同步成功！当前内存收录: " + sExactTable.size() + " 条");
                                return;
                            }
                        }
                    } catch (Throwable e) {
                        PLog.w(TAG, "拉取 SubID 节点失败 [" + endpoint + "]: " + e.getMessage());
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
            } catch (Throwable t) {
                PLog.w(TAG, "静默 OTA 增量同步异常: " + t.getMessage());
            }
        }).start();
    }

    private static void parseJsonDictionary(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        JSONObject root = new JSONObject(sb.toString());

        Set<String> standardKeys = new HashSet<>(Arrays.asList(
                "summary", "platform", "arch", "version", "date", "channel", "packages", "versions", "is_beta"
        ));

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
                detail.channel = obj.optString("channel", "");

                if (obj.has("is_beta")) {
                    detail.isBeta = obj.optBoolean("is_beta");
                } else {
                    detail.isBeta = null;
                }

                JSONArray pkgArr = obj.optJSONArray("packages");
                if (pkgArr != null) {
                    for (int i = 0; i < pkgArr.length(); i++) detail.packages.add(pkgArr.getString(i));
                }
                JSONArray verArr = obj.optJSONArray("versions");
                if (verArr != null) {
                    for (int i = 0; i < verArr.length(); i++) detail.versions.add(verArr.getString(i));
                }

                Iterator<String> objKeys = obj.keys();
                while (objKeys.hasNext()) {
                    String ok = objKeys.next();
                    if (!standardKeys.contains(ok)) {
                        Object extraVal = obj.opt(ok);
                        if (extraVal != null) {
                            String extraStr = extraVal.toString().trim();
                            if (!extraStr.isEmpty() && !extraStr.equals("null") && !extraStr.equals("[]") && !extraStr.equals("{}")) {
                                detail.extraFields.put(ok, extraStr);
                            }
                        }
                    }
                }

                if (detail.summary.isEmpty()) detail.summary = detail.version;
                if (detail.summary.isEmpty()) detail.summary = "SubID " + key;
            } else if (valObj instanceof String) {
                String str = ((String) valObj).trim();
                detail.summary = str;
                detail.version = str;
                detail.isBeta = null;
            } else continue;

            sExactTable.put(key, detail.summary);
            sDetailTable.put(key, detail);

            if (key.matches("\\d+")) {
                try {
                    sNavMap.put(Long.parseLong(key), detail);
                } catch (Throwable ignored) {}
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
                Object val = obj.get(uin);

                List<UserDeviceRecord> records = new CopyOnWriteArrayList<>();
                if (val instanceof JSONArray) {
                    JSONArray arr = (JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.optJSONObject(i);
                        if (r != null) {
                            long sub = r.optLong("sub", 0L);
                            long t = r.optLong("t", 0L);
                            if (sub > 0) records.add(new UserDeviceRecord(sub, t));
                        }
                    }
                } else if (val instanceof Number) {
                    long sub = ((Number) val).longValue();
                    if (sub > 0) records.add(new UserDeviceRecord(sub, f.lastModified()));
                }

                if (!records.isEmpty()) {
                    sUserRecords.put(uin, records);
                }
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

            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<UserDeviceRecord>> entry : sUserRecords.entrySet()) {
                JSONArray arr = new JSONArray();
                for (UserDeviceRecord record : entry.getValue()) {
                    JSONObject r = new JSONObject();
                    r.put("sub", record.subId);
                    r.put("t", record.timestamp);
                    arr.put(r);
                }
                root.put(entry.getKey(), arr);
            }

            try (FileOutputStream fos = new FileOutputStream(f, false);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(root.toString());
                osw.flush();
                fos.getFD().sync();
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
            long[] res = readVarint(data, pos, limit);
            if (res == null) break;
            int fn = (int) (res[0] >>> 3), wt = (int) (res[0] & 7);
            pos = (int) res[1];
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

    private static long[] readVarint(byte[] data, int pos, int limit) {
        long result = 0;
        for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return new long[]{result, pos};
        }
        return null;
    }
}
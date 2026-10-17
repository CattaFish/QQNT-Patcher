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
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class QQVersionModule implements IPatchModule {

    private static final String TAG = "QqVersion";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    public static final String UNKNOWN_STATUS = "未捕获到客户端版本";

    // 双源独立镜像组：多镜像冗余容灾，国内直连更稳健
    private static final String[][] REMOTE_SOURCE_GROUPS = new String[][]{
            {
                    "https://ghproxy.net/https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json",
                    "https://ghfast.top/https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json",
                    "https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json",
                    "https://cdn.jsdelivr.net/gh/CattaFish/QQNT-Patcher@main/assets/subid.json"
            },
            {
                    "https://ghproxy.net/https://raw.githubusercontent.com/CattaFish/qqDecoder/main/data/subid.json",
                    "https://ghfast.top/https://raw.githubusercontent.com/CattaFish/qqDecoder/main/data/subid.json",
                    "https://raw.githubusercontent.com/CattaFish/qqDecoder/main/data/subid.json",
                    "https://cdn.jsdelivr.net/gh/CattaFish/qqDecoder@main/data/subid.json"
            }
    };

    public static class SubidDetail {
        public String subId;
        public String summary;
        public String platform;
        public String arch;
        public String version;
        public String date;
        public String channel;
        public List<String> packages = new ArrayList<>();
        public List<String> versions = new ArrayList<>();
        public Map<String, String> extraFields = new LinkedHashMap<>();
    }

    public static class InferResult {
        public boolean isExact = false;
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

    private static final Map<String, Long> sLastSubidByUin = new ConcurrentHashMap<>();
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
        return inferSubid(subId).displaySummary;
    }

    public static InferResult inferSubid(long subId) {
        InferResult res = new InferResult();
        String sv = String.valueOf(subId);

        SubidDetail exact = sDetailTable.get(sv);
        if (exact != null) {
            res.isExact = true;
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
            res.suspectedPlatform = floor.getValue().platform;
            res.suspectedArch = floor.getValue().arch;
            res.suspectedVersion = floor.getValue().version;
            res.warningNote = "此 SubID (" + subId + ") 高于本地收录最高版本，推测为近期发布的更新版本，可能不准确！";
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
            res.suspectedPlatform = ref.platform;
            res.suspectedArch = ref.arch;

            String vRef = ref.version != null ? ref.version : "";
            String[] parts = vRef.split("\\.");
            String guessVer = (parts.length >= 3) ? (parts[0] + "." + parts[1] + "." + parts[2]) : vRef;
            res.suspectedVersion = guessVer;

            String platArch = ref.platform != null ? ref.platform : "";
            if (ref.arch != null && !ref.arch.isEmpty()) platArch += "-" + ref.arch;

            res.warningNote = "未精确收录此 SubID，根据临近发版区间推测可能为 " + platArch + " " + guessVer + "，推测仅供参考！";
            res.displaySummary = "未匹配 (推测: " + platArch + " " + guessVer + "?)";
            return res;
        }

        if (ceil != null) {
            res.suspectedPlatform = ceil.getValue().platform;
            res.suspectedArch = ceil.getValue().arch;
            res.suspectedVersion = ceil.getValue().version;
            res.warningNote = "此 SubID 较低，推测为历史未收录老版本，可能不准确！";
            res.displaySummary = "未匹配 (推测为历史老版?)";
            return res;
        }

        res.displaySummary = "未匹配版本 (" + subId + ")";
        res.warningNote = "完全未知号段，无法进行有效区间推测。";
        return res;
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
                // 等待 6 秒平稳冷启动
                Thread.sleep(6000);

                File extFile = getExternalDictionaryFile(context);
                if (extFile == null) return;

                JSONObject localJson = new JSONObject();
                if (extFile.exists() && extFile.length() > 0) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(extFile), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) sb.append(l);
                        localJson = new JSONObject(sb.toString());
                    } catch (Throwable t) {
                        PLog.w(TAG, "读取本地已存字典异常，重新拉取: " + t.getMessage());
                    }
                }

                int totalNewCount = 0;

                // 遍历两个数据源组
                for (String[] mirrors : REMOTE_SOURCE_GROUPS) {
                    boolean groupSuccess = false;
                    for (String urlStr : mirrors) {
                        HttpURLConnection conn = null;
                        try {
                            long ts = System.currentTimeMillis();
                            String sep = urlStr.contains("?") ? "&" : "?";
                            URL url = new URL(urlStr + sep + "_nocache=" + ts);

                            conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setInstanceFollowRedirects(true);
                            conn.setConnectTimeout(8000); // 放宽至 8 秒防超时
                            conn.setReadTimeout(8000);
                            conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client");
                            conn.setRequestProperty("Cache-Control", "no-cache");
                            conn.setRequestProperty("Pragma", "no-cache");

                            int code = conn.getResponseCode();

                            // 针对部分 301/302 手动跟随重定向保障
                            if (code == 301 || code == 302 || code == 307 || code == 308) {
                                String redirectUrl = conn.getHeaderField("Location");
                                if (redirectUrl != null && !redirectUrl.isEmpty()) {
                                    conn.disconnect();
                                    url = new URL(redirectUrl);
                                    conn = (HttpURLConnection) url.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(8000);
                                    conn.setReadTimeout(8000);
                                    conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client");
                                    code = conn.getResponseCode();
                                }
                            }

                            if (code == 200) {
                                StringBuilder sb = new StringBuilder();
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) sb.append(line);
                                }
                                JSONObject remoteJson = new JSONObject(sb.toString());

                                Iterator<String> it = remoteJson.keys();
                                while (it.hasNext()) {
                                    String subid = it.next();
                                    Object rVal = remoteJson.opt(subid);
                                    if (rVal == null) continue;

                                    if (!localJson.has(subid) || !sExactTable.containsKey(subid)) {
                                        localJson.put(subid, rVal);
                                        totalNewCount++;
                                    } else {
                                        Object lVal = localJson.opt(subid);
                                        if (lVal instanceof String && rVal instanceof JSONObject) {
                                            localJson.put(subid, rVal);
                                            totalNewCount++;
                                        } else if (lVal instanceof JSONObject && rVal instanceof JSONObject) {
                                            JSONObject lObj = (JSONObject) lVal;
                                            JSONObject rObj = (JSONObject) rVal;
                                            boolean objModified = false;

                                            Iterator<String> rkIt = rObj.keys();
                                            while (rkIt.hasNext()) {
                                                String rk = rkIt.next();
                                                Object rFieldVal = rObj.opt(rk);
                                                Object lFieldVal = lObj.opt(rk);

                                                if (rFieldVal == null) continue;

                                                if (!lObj.has(rk) || lFieldVal == null) {
                                                    lObj.put(rk, rFieldVal);
                                                    objModified = true;
                                                } else if (lFieldVal instanceof String && rFieldVal instanceof String) {
                                                    String rStr = ((String) rFieldVal).trim();
                                                    String lStr = ((String) lFieldVal).trim();
                                                    if (!rStr.isEmpty() && !rStr.equals(lStr)) {
                                                        if ("channel".equals(rk)) {
                                                            lObj.put(rk, rStr);
                                                            objModified = true;
                                                        } else if ("version".equals(rk)) {
                                                            if (!lStr.contains(rStr)) {
                                                                String combined = lStr + " / " + rStr;
                                                                lObj.put("version", combined);
                                                                if (lObj.has("summary")) {
                                                                    lObj.put("summary", lObj.optString("summary").replace(lStr, combined));
                                                                }
                                                                objModified = true;
                                                            }
                                                        } else {
                                                            lObj.put(rk, rStr);
                                                            objModified = true;
                                                        }
                                                    }
                                                } else if (lFieldVal instanceof JSONArray && rFieldVal instanceof JSONArray) {
                                                    JSONArray lArr = (JSONArray) lFieldVal;
                                                    JSONArray rArr = (JSONArray) rFieldVal;
                                                    Set<String> set = new LinkedHashSet<>();
                                                    for (int i = 0; i < lArr.length(); i++) set.add(lArr.optString(i));
                                                    int beforeSize = set.size();
                                                    for (int i = 0; i < rArr.length(); i++) set.add(rArr.optString(i));
                                                    if (set.size() > beforeSize) {
                                                        JSONArray newArr = new JSONArray();
                                                        for (String s : set) newArr.put(s);
                                                        lObj.put(rk, newArr);
                                                        objModified = true;
                                                    }
                                                } else if (!lFieldVal.equals(rFieldVal)) {
                                                    lObj.put(rk, rFieldVal);
                                                    objModified = true;
                                                }
                                            }
                                            if (objModified) totalNewCount++;
                                        }
                                    }
                                }
                                groupSuccess = true;
                                break; // 本镜像获取成功，继续下一个源组
                            } else {
                                PLog.w(TAG, "节点返回非 200 (HTTP " + code + "): " + urlStr);
                            }
                        } catch (Throwable e) {
                            PLog.w(TAG, "节点连接失败 [" + urlStr + "]: " + e.getMessage());
                        } finally {
                            if (conn != null) conn.disconnect();
                        }
                    }
                    if (!groupSuccess) {
                        PLog.w(TAG, "当前镜像组全量失败，跳过该组更新");
                    }
                }

                if (totalNewCount > 0) {
                    // 原子写盘（防写到一半被杀进程截断成 0 字节）
                    File tmpFile = new File(extFile.getAbsolutePath() + ".tmp");
                    try (FileOutputStream fos = new FileOutputStream(tmpFile);
                         OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                        osw.write(localJson.toString(2));
                        osw.flush();
                    }

                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        if (extFile.exists()) extFile.delete();
                        if (!tmpFile.renameTo(extFile)) {
                            copyFile(tmpFile, extFile);
                            tmpFile.delete();
                        }
                    }

                    // 热加载进内存
                    try (InputStream is = new FileInputStream(extFile)) {
                        parseJsonDictionary(is);
                    }
                    PLog.i(TAG, "已成功完成 SubID 字典静默增量更新，吸收新版本/更新渠道: " + totalNewCount + " 处，当前总数: " + sExactTable.size());
                } else {
                    PLog.i(TAG, "SubID 字典已是最新，无新增版本或渠道 (当前收录: " + sExactTable.size() + " 条)");
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
                "summary", "platform", "arch", "version", "date", "channel", "packages", "versions"
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

            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(obj.toString());
                osw.flush();
            }
            if (tmp.exists() && tmp.length() > 0) {
                if (f.exists()) f.delete();
                if (!tmp.renameTo(f)) {
                    copyFile(tmp, f);
                    tmp.delete();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void copyFile(File src, File dest) {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
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
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
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class QQVersionModule implements IPatchModule {

    private static final String TAG = "QqVersion";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    public static final String UNKNOWN_STATUS = "未捕获到客户端版本";

    // 内存缓存: uin -> subid
    private static final Map<String, Long> sLastSubidByUin = new ConcurrentHashMap<>();
    // 内存缓存: uin:seq:rnd -> subid
    private static final Map<String, Long> sSubidByMsgKey = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(200, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 1000;
                }
            }
    );

    // 字典映射表 (纯净文本，无 Emoji)
    private static final Map<String, String> sExactTable = new ConcurrentHashMap<>();
    private static final Map<String, String> sFuzzyTable = new ConcurrentHashMap<>();
    private static final AtomicBoolean sDictLoaded = new AtomicBoolean(false);

    // 防抖落盘机制 (3 秒延迟防抖)
    private static final Handler sDebounceHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sSaveRunnable = () -> new Thread(QQVersionModule::doSaveSubidCache).start();

    @Override public String getId() { return "qq_version"; }
    @Override public String getName() { return "发送者 QQ 版本识别"; }
    @Override public boolean defaultEnabled() { return false; } // 默认关闭，需用户在 GUI 手动开启

    @Override
    public String getSubName() {
        if (!isEnabled()) {
            return "在好友设置与群成员设置展示对方客户端版本";
        }
        int count = sLastSubidByUin.size();
        return "在好友设置与群成员设置展示对方客户端版本 (已捕获 " + count + " 人)";
    }

    @Override
    public void onInit(Context context) {
        ensureDictionaryLoaded(context);
        loadLocalSubidCache(context);
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
            byte[] content = getProtoBytes(qqMsg, 2);
            if (head == null || content == null) return buf;

            long uin = getProtoVarint(head, 1);
            long subId = getProtoVarint(head, 4);

            if (subId > 0 && uin > 10000L) {
                long msgType = getProtoVarint(content, 1);
                long seq = (msgType == 82) ? getProtoVarint(content, 5) : getProtoVarint(content, 11);
                long rnd = getProtoVarint(content, 4);

                String uinStr = String.valueOf(uin);
                sLastSubidByUin.put(uinStr, subId);
                sSubidByMsgKey.put(uinStr + ":" + seq + ":" + rnd, subId);

                // 3 秒防抖落盘：平稳安全，绝不丢失数据
                scheduleDebounceSave();
            }
        } catch (Throwable t) {
            PLog.d(TAG, "解析 MsgPush subid 异常: " + t.getMessage());
        }

        return buf;
    }

    public static String getVersionByUin(String uin) {
        if (uin == null || uin.isEmpty()) {
            return "未知用户";
        }
        Long subId = sLastSubidByUin.get(uin);
        if (subId == null || subId <= 0) {
            return UNKNOWN_STATUS;
        }
        return lookupVersion(subId);
    }

    public static String lookupVersion(long subId) {
        String sv = String.valueOf(subId);
        String hit = sExactTable.get(sv);
        if (hit != null && !hit.isEmpty()) {
            return hit;
        }

        if (sv.length() >= 6) {
            String mid = sv.substring(2, 6);
            String fuzzyHit = sFuzzyTable.get(mid);
            if (fuzzyHit != null && !fuzzyHit.isEmpty()) {
                return fuzzyHit + "~";
            }
        }

        return "未知版本 (" + subId + ")";
    }

    // ========================== 字典与缓存加载 ==========================

    private static synchronized void ensureDictionaryLoaded(Context context) {
        if (sDictLoaded.getAndSet(true)) return;

        loadBuiltinFallback();

        // 1. 读取内置 assets 资源字典
        String[] dictNames = new String[]{"subid.json", "builtin_subid.json", "tim_subid.json", "win_subid.json"};
        for (String name : dictNames) {
            try (InputStream is = PatchAssetHelper.openStream(context, name)) {
                if (is != null) {
                    parseJsonDictionary(is, name);
                    PLog.i(TAG, "已成功装载内置字典: " + name);
                }
            } catch (Throwable t) {
                PLog.w(TAG, "读取内置字典 " + name + " 异常: " + t.getMessage());
            }
        }

        // 2. 免重编外部字典热加载 (检查手机存储 /zzz/subid_dict.json)
        File extDictFile = getExternalDictionaryFile(context);
        if (extDictFile != null && extDictFile.exists() && extDictFile.isFile()) {
            try (InputStream fis = new FileInputStream(extDictFile)) {
                parseJsonDictionary(fis, extDictFile.getName());
                PLog.i(TAG, "已优先热加载外部扩展字典: " + extDictFile.getAbsolutePath());
            } catch (Throwable t) {
                PLog.w(TAG, "加载外部扩展字典异常: " + t.getMessage());
            }
        }

        PLog.i(TAG, "版本字典初始化完成，当前精确项: " + sExactTable.size() + ", 模糊项: " + sFuzzyTable.size());
    }

    private static void parseJsonDictionary(InputStream is, String sourceName) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        JSONObject root = new JSONObject(sb.toString());

        // 决定前缀
        String prefix = "QQ ";
        if (sourceName.contains("tim")) prefix = "TIM ";
        else if (sourceName.contains("win") || sourceName.contains("pc")) prefix = "QQ(PC) ";

        // 仅把 JSON 当作【subid -> 版本名】的翻译词典使用！
        // 彻底绝收外来的 last_subid 伪造用户数据，保证本机捕获数据的纯净与真实
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            // 过滤掉原作者导出的历史记录 key
            if ("subid_map".equals(key) || "last_subid".equals(key)) continue;

            String verStr = root.optString(key, "");
            if (!verStr.isEmpty()) {
                String fullVer = prefix + verStr;
                sExactTable.put(key, fullVer);
                if (key.length() >= 6) {
                    String mid = key.substring(2, 6);
                    if (!sFuzzyTable.containsKey(mid)) {
                        sFuzzyTable.put(mid, fullVer);
                    }
                }
            }
        }
    }

    private static void loadBuiltinFallback() {
        sExactTable.put("537395444", "QQ 9.3.70");
        sExactTable.put("537395443", "QQ 9.3.65");
        sExactTable.put("537395440", "QQ 9.3.55");
        sExactTable.put("537395438", "QQ 9.3.50");
        sExactTable.put("537394470", "QQ 9.3.30");
        sExactTable.put("537393106", "QQ 9.3.20");
        sExactTable.put("537393102", "QQ 9.3.15");
        sExactTable.put("537393100", "QQ 9.3.10");
        sExactTable.put("537339359", "TIM 4.0.95");
        sExactTable.put("537333143", "TIM 4.0.70");
        sExactTable.put("537243416", "QQ 8.9.88");
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
            PLog.i(TAG, "从本地文件载入历史 subid 缓存: " + sLastSubidByUin.size() + " 条");
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

    private static File getCacheFile(Context context) {
        return new File(getZzzDir(context), "subid_cache.json");
    }

    private static File getExternalDictionaryFile(Context context) {
        return new File(getZzzDir(context), "subid_dict.json");
    }

    // ========================== 基础 Protobuf 解析工具 ==========================

    private static byte[] subArray(byte[] src, int start) {
        if (src == null || start >= src.length) return new byte[0];
        byte[] d = new byte[src.length - start];
        System.arraycopy(src, start, d, 0, d.length);
        return d;
    }

    private static byte[] getProtoBytes(byte[] data, int targetField) {
        if (data == null) return null;
        int pos = 0;
        int limit = data.length;
        while (pos < limit) {
            long[] tagRes = readVarint(data, pos, limit);
            if (tagRes == null) break;
            int fn = (int) (tagRes[0] >>> 3);
            int wt = (int) (tagRes[0] & 7);
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
            } else if (wt == 1) {
                pos += 8;
            } else if (wt == 5) {
                pos += 4;
            } else break;
        }
        return null;
    }

    private static long getProtoVarint(byte[] data, int targetField) {
        if (data == null) return 0L;
        int pos = 0;
        int limit = data.length;
        while (pos < limit) {
            long[] tagRes = readVarint(data, pos, limit);
            if (tagRes == null) break;
            int fn = (int) (tagRes[0] >>> 3);
            int wt = (int) (tagRes[0] & 7);
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
            } else if (wt == 1) {
                pos += 8;
            } else if (wt == 5) {
                pos += 4;
            } else break;
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
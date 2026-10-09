package com.tencent.qqnt.patch.modules.chat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.tencent.qqnt.kernel.nativeinterface.FileElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AutoRemarkApkModule implements IPatchModule {

    private static final String TAG = "AutoRemarkApk";
    private static final String CMD_FILE_UPLOAD_RESP = "OidbSvcTrpcTcp.0xe37_800";
    private static final Pattern APK_DIRTY_SUFFIX_PATTERN = Pattern.compile("(?i).*\\.apk\\.\\d+$");

    @Override public String getId() { return "auto_remark_apk"; }
    @Override public String getName() { return "上传 APK 自动重命名"; }
    @Override public String getSubName() { return "上传的文件将重命名为：应用名_版本号.APK"; }
    @Override public boolean defaultEnabled() { return false; }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled() || elements == null || elements.isEmpty()) return;
        Context context = AppContext.get();
        if (context == null) return;

        for (MsgElement elem : elements) {
            if (elem == null || elem.fileElement == null) continue;
            FileElement fe = elem.fileElement;

            if (fe.fileName != null && fe.fileName.toLowerCase().endsWith(".apk")) {
                if (fe.filePath != null && !fe.filePath.trim().isEmpty()) {
                    File localFile = new File(fe.filePath);
                    if (localFile.exists() && localFile.isFile()) {
                        renameApkFile(fe, localFile, context);
                        continue;
                    }
                }
                fe.fileName = fe.fileName.replaceAll("(?i)\\.apk$", ".APK");
            }
        }
    }

    private void renameApkFile(FileElement fileElement, File localFile, Context context) {
        try {
            String originalName = fileElement.fileName;
            String parsedName = null;

            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageArchiveInfo(localFile.getAbsolutePath(), PackageManager.GET_META_DATA);

            if (packageInfo != null && packageInfo.applicationInfo != null) {
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                appInfo.sourceDir = localFile.getAbsolutePath();
                appInfo.publicSourceDir = localFile.getAbsolutePath();

                CharSequence label = appInfo.loadLabel(pm);
                String appName = label != null ? label.toString() : "";
                String versionName = packageInfo.versionName != null ? packageInfo.versionName : "未知版本";

                String safeAppName = appName.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                if (!safeAppName.isEmpty()) {
                    parsedName = safeAppName + "_" + versionName + ".APK";
                }
            }

            if (parsedName != null) {
                fileElement.fileName = parsedName;
            } else if (originalName != null && !originalName.isEmpty()) {
                fileElement.fileName = originalName.replaceAll("(?i)\\.apk$", ".APK");
            } else {
                fileElement.fileName = "应用_" + System.currentTimeMillis() + ".APK";
            }

            PLog.i(TAG, "[重命名] [" + originalName + "] -> [" + fileElement.fileName + "]");
        } catch (Throwable t) {
            PLog.e(TAG, "[重命名异常]", t);
            if (fileElement.fileName != null) {
                fileElement.fileName = fileElement.fileName.replaceAll("(?i)\\.apk$", ".APK");
            }
        }
    }

    public static void onDispatchRespMsg(Object msfMessagePair) {
        if (!ConfigManager.isModuleEnabled("auto_remark_apk", false) || msfMessagePair == null) {
            return;
        }

        try {
            Class<?> pairClz = msfMessagePair.getClass();
            Field fromMsgField = pairClz.getField("fromServiceMsg");
            Object fromServiceMsg = fromMsgField.get(msfMessagePair);
            if (fromServiceMsg == null) return;

            Method getCmdM = fromServiceMsg.getClass().getMethod("getServiceCmd");
            String cmd = (String) getCmdM.invoke(fromServiceMsg);

            if (!CMD_FILE_UPLOAD_RESP.equals(cmd)) return;

            Method getWupBufM = fromServiceMsg.getClass().getMethod("getWupBuffer");
            byte[] wupBuf = (byte[]) getWupBufM.invoke(fromServiceMsg);
            if (wupBuf == null || wupBuf.length <= 4) return;

            int offset = 0;
            int totalLen = ((wupBuf[0] & 0xFF) << 24) | ((wupBuf[1] & 0xFF) << 16) | ((wupBuf[2] & 0xFF) << 8) | (wupBuf[3] & 0xFF);
            if (totalLen == wupBuf.length) {
                offset = 4;
            }

            ProtoNode root = ProtoNode.parse(wupBuf, offset, wupBuf.length);
            if (root != null) {
                boolean modified = ProtoNode.fixSuffixRecursive(root);
                if (modified) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(wupBuf.length);
                    if (offset == 4) {
                        bos.write(0); bos.write(0); bos.write(0); bos.write(0);
                    }
                    ProtoNode.writeTo(root, bos);
                    byte[] newBytes = bos.toByteArray();

                    if (offset == 4) {
                        int newTotal = newBytes.length;
                        newBytes[0] = (byte) ((newTotal >>> 24) & 0xFF);
                        newBytes[1] = (byte) ((newTotal >>> 16) & 0xFF);
                        newBytes[2] = (byte) ((newTotal >>> 8) & 0xFF);
                        newBytes[3] = (byte) (newTotal & 0xFF);
                    }

                    try {
                        Method putM = fromServiceMsg.getClass().getMethod("putWupBuffer", byte[].class);
                        putM.invoke(fromServiceMsg, (Object) newBytes);
                    } catch (Throwable ignored) {
                        Field f = fromServiceMsg.getClass().getDeclaredField("wupBuffer");
                        f.setAccessible(true);
                        f.set(fromServiceMsg, newBytes);
                    }
                    PLog.i(TAG, "已成功拦截并清洗 0xe37_800 回包中的 .APK.1 篡改后缀！");
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "处理 0xe37_800 回包异常", t);
        }
    }

    private static String fixSuffix(String name) {
        if (name == null) return null;
        if (APK_DIRTY_SUFFIX_PATTERN.matcher(name).matches()) {
            return name.replaceFirst("(?i)\\.apk\\.\\d+$", ".APK");
        }
        return name;
    }

    private static class FieldItem {
        int tag;
        int wireType;
        int fieldNumber;
        Object value;

        FieldItem(int tag, int wireType, int fieldNumber, Object value) {
            this.tag = tag;
            this.wireType = wireType;
            this.fieldNumber = fieldNumber;
            this.value = value;
        }
    }

    private static class ProtoNode {
        List<FieldItem> fields = new ArrayList<>();

        static ProtoNode parse(byte[] data, int start, int end) {
            ProtoNode node = new ProtoNode();
            int pos = start;
            while (pos < end) {
                long[] tagRes = readVarint(data, pos, end);
                if (tagRes == null) return null;
                long rawTag = tagRes[0];
                pos = (int) tagRes[1];

                int wireType = (int) (rawTag & 7);
                int fieldNum = (int) (rawTag >>> 3);
                if (fieldNum <= 0) return null;

                if (wireType == 0) {
                    long[] valRes = readVarint(data, pos, end);
                    if (valRes == null) return null;
                    node.fields.add(new FieldItem((int) rawTag, wireType, fieldNum, valRes[0]));
                    pos = (int) valRes[1];
                } else if (wireType == 1) {
                    if (pos + 8 > end) return null;
                    byte[] b = new byte[8];
                    System.arraycopy(data, pos, b, 0, 8);
                    node.fields.add(new FieldItem((int) rawTag, wireType, fieldNum, b));
                    pos += 8;
                } else if (wireType == 2) {
                    long[] lenRes = readVarint(data, pos, end);
                    if (lenRes == null) return null;
                    int len = (int) lenRes[0];
                    pos = (int) lenRes[1];
                    if (len < 0 || pos + len > end) return null;

                    if (len > 0 && canParseAsMessage(data, pos, pos + len)) {
                        ProtoNode child = parse(data, pos, pos + len);
                        if (child != null) {
                            node.fields.add(new FieldItem((int) rawTag, wireType, fieldNum, child));
                            pos += len;
                            continue;
                        }
                    }

                    byte[] raw = new byte[len];
                    System.arraycopy(data, pos, raw, 0, len);
                    node.fields.add(new FieldItem((int) rawTag, wireType, fieldNum, raw));
                    pos += len;
                } else if (wireType == 5) {
                    if (pos + 4 > end) return null;
                    byte[] b = new byte[4];
                    System.arraycopy(data, pos, b, 0, 4);
                    node.fields.add(new FieldItem((int) rawTag, wireType, fieldNum, b));
                    pos += 4;
                } else {
                    return null;
                }
            }
            return node;
        }

        static boolean fixSuffixRecursive(ProtoNode node) {
            boolean modified = false;
            for (FieldItem item : node.fields) {
                if (item.wireType == 2) {
                    if (item.value instanceof ProtoNode) {
                        if (fixSuffixRecursive((ProtoNode) item.value)) {
                            modified = true;
                        }
                    } else if (item.value instanceof byte[]) {
                        byte[] raw = (byte[]) item.value;
                        if (raw.length >= 6) {
                            try {
                                String str = new String(raw, StandardCharsets.UTF_8);
                                if (APK_DIRTY_SUFFIX_PATTERN.matcher(str).matches()) {
                                    String fixed = fixSuffix(str);
                                    if (!str.equals(fixed)) {
                                        item.value = fixed.getBytes(StandardCharsets.UTF_8);
                                        modified = true;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            return modified;
        }

        static void writeTo(ProtoNode node, ByteArrayOutputStream out) throws IOException {
            for (FieldItem item : node.fields) {
                writeVarint(out, item.tag);
                if (item.wireType == 0) {
                    writeVarint(out, (Long) item.value);
                } else if (item.wireType == 1 || item.wireType == 5) {
                    out.write((byte[]) item.value);
                } else if (item.wireType == 2) {
                    if (item.value instanceof ProtoNode) {
                        ByteArrayOutputStream subOut = new ByteArrayOutputStream();
                        writeTo((ProtoNode) item.value, subOut);
                        byte[] subBytes = subOut.toByteArray();
                        writeVarint(out, subBytes.length);
                        out.write(subBytes);
                    } else {
                        byte[] b = (byte[]) item.value;
                        writeVarint(out, b.length);
                        out.write(b);
                    }
                }
            }
        }

        private static boolean canParseAsMessage(byte[] data, int start, int end) {
            int pos = start;
            if (pos >= end) return false;
            int count = 0;
            while (pos < end) {
                long[] tagRes = readVarint(data, pos, end);
                if (tagRes == null) return false;
                long tag = tagRes[0];
                pos = (int) tagRes[1];

                int wireType = (int) (tag & 7);
                int fieldNum = (int) (tag >>> 3);
                if (fieldNum <= 0 || fieldNum > 536870911) return false;

                count++;
                if (wireType == 0) {
                    long[] v = readVarint(data, pos, end);
                    if (v == null) return false;
                    pos = (int) v[1];
                } else if (wireType == 1) {
                    pos += 8;
                    if (pos > end) return false;
                } else if (wireType == 2) {
                    long[] lenRes = readVarint(data, pos, end);
                    if (lenRes == null) return false;
                    int len = (int) lenRes[0];
                    pos = (int) lenRes[1];
                    if (len < 0 || pos + len > end) return false;
                    pos += len;
                } else if (wireType == 5) {
                    pos += 4;
                    if (pos > end) return false;
                } else {
                    return false;
                }
            }
            return pos == end && count > 0;
        }

        private static long[] readVarint(byte[] data, int pos, int limit) {
            long result = 0;
            for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return new long[]{result, pos};
                }
            }
            return null;
        }

        private static void writeVarint(ByteArrayOutputStream out, long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) (value & 0x7F));
        }
    }
}

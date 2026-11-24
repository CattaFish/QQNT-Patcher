package com.tencent.qqnt.patch.plugin;

import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RKeyManager {

    private static final String TAG = "RKeyManager";
    private static final String CMD_RKEY = "OidbSvcTrpcTcp.0x9067_202";

    // 匹配合法的 URL-Safe Base64 密钥，并在遇到非 Base64 字符（如二进制乱码）时立即截断
    private static final Pattern RKEY_PATTERN = Pattern.compile("&?rkey=([A-Za-z0-9_-]{30,120})");

    private static volatile String sFriendRKey = "";
    private static volatile String sGroupRKey = "";

    public static String getFriendRKey() { return sFriendRKey; }
    public static String getGroupRKey() { return sGroupRKey; }

    public static void onDispatchRespMsg(Object msfMessagePair) {
        if (msfMessagePair == null) return;
        try {
            Class<?> pairClz = msfMessagePair.getClass();
            Field fromMsgField = pairClz.getField("fromServiceMsg");
            Object fromServiceMsg = fromMsgField.get(msfMessagePair);
            if (fromServiceMsg == null) return;

            Method getCmdM = fromServiceMsg.getClass().getMethod("getServiceCmd");
            String cmd = (String) getCmdM.invoke(fromServiceMsg);

            if (CMD_RKEY.equals(cmd)) {
                Method getWupBufM = fromServiceMsg.getClass().getMethod("getWupBuffer");
                byte[] wupBuf = (byte[]) getWupBufM.invoke(fromServiceMsg);
                if (wupBuf != null && wupBuf.length > 4) {
                    parseRKeyBuffer(wupBuf);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void parseRKeyBuffer(byte[] buf) {
        try {
            int offset = (buf[0] == 0) ? 4 : 0;
            byte[] data = new byte[buf.length - offset];
            System.arraycopy(buf, offset, data, 0, data.length);

            String content = new String(data, StandardCharsets.ISO_8859_1);
            Matcher m = RKEY_PATTERN.matcher(content);

            if (m.find()) {
                // 命中第一个密钥：通常为好友私聊密钥
                sFriendRKey = "&rkey=" + m.group(1);
                
                if (m.find()) {
                    // 命中第二个密钥：群聊密钥
                    sGroupRKey = "&rkey=" + m.group(1);
                } else {
                    sGroupRKey = sFriendRKey;
                }

                PLog.i(TAG, "已成功捕获纯净高清图片密钥:");
                PLog.i(TAG, "  ├─ 私聊 RKey: " + sFriendRKey);
                PLog.i(TAG, "  └─ 群聊 RKey: " + sGroupRKey);
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析 RKey 报文异常", t);
        }
    }
}
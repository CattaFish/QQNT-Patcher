package com.tencent.qqnt.patch.plugin;

import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.nio.charset.StandardCharsets;

public class MsfPushEventHandler {

    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";

    public static void onMsfPush(String cmd, byte[] buf) {
        if (buf == null || !CMD_MSG_PUSH.equals(cmd)) return;
        if (!FeatureConfig.has("script") || !PluginManager.hasActivePlugins()) return;

        dispatchNudgeAndShutUpEvents(buf);
    }

    private static void dispatchNudgeAndShutUpEvents(byte[] buf) {
        try {
            byte[] qqMsgBytes = Proto.getBytes(buf, 1);
            if (qqMsgBytes == null) return;
            byte[] headerBytes = Proto.getBytes(qqMsgBytes, 2);
            if (headerBytes == null) return;

            long cmd1 = Proto.getVarint(headerBytes, 1);
            long cmd2 = Proto.getVarint(headerBytes, 2);

            byte[] bodyBytes = Proto.getBytes(qqMsgBytes, 3);
            if (bodyBytes == null) return;
            byte[] subBytes = Proto.getBytes(bodyBytes, 2);
            if (subBytes == null) return;

            if (cmd1 == 732 && cmd2 == 12) {
                long troopUin = Proto.getVarint(subBytes, 1);
                String opUid = Proto.getString(subBytes, 4);
                byte[] shutUpInfo = Proto.getBytes(subBytes, 5);
                if (shutUpInfo != null) {
                    byte[] inner = Proto.getBytes(shutUpInfo, 3);
                    if (inner != null) {
                        String memberUid = Proto.getString(inner, 1);
                        long time = Proto.getVarint(inner, 2);
                        PluginManager.dispatchTroopShutUp(
                                String.valueOf(troopUin),
                                getUin(memberUid),
                                time,
                                getUin(opUid)
                        );
                    }
                }
                return;
            }

            int chatType = 0;
            String peerUin = "";
            String fromUin = "";
            String toUin = "";

            byte[] head1 = Proto.getBytes(qqMsgBytes, 1);

            if (cmd1 == 732 && cmd2 == 20) {
                chatType = 2;
                peerUin = readUin(head1, 1);
                String content = new String(subBytes, StandardCharsets.UTF_8);
                fromUin = extractQQ(content, "1");
                toUin = extractQQ(content, "2");
            } else if (cmd1 == 528 && cmd2 == 290) {
                chatType = 1;
                peerUin = readUin(head1, 1);
                fromUin = peerUin;
                toUin = extractToUinFromField7(subBytes);
            }

            if (chatType != 0) {
                String myUin = MsgSender.getMyUin();
                if (isValidQQ(fromUin) && isValidQQ(toUin) && (toUin.equals(myUin) || myUin.isEmpty())) {
                    PLog.i("PaiYiPai", "成功捕获拍一拍事件: peer=" + peerUin + ", 来自=" + fromUin + ", 目标=" + toUin);
                    PluginManager.dispatchPaiYiPai(peerUin, chatType, fromUin);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String getUin(String uid) {
        if (uid == null || uid.isEmpty()) return "";
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                String uin = api.getUinFromUid(uid);
                if (uin != null && !uin.isEmpty() && !uin.equals("0")) return uin;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String readUin(byte[] data, int targetField) {
        if (data == null) return "";
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long val = reader.readVarint();
                if (field == targetField) return String.valueOf(val);
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub == null) break;
                if (field == targetField) {
                    return new String(sub, StandardCharsets.UTF_8);
                }
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return "";
    }

    private static String extractQQ(String target, String type) {
        if (target == null || target.isEmpty()) return "";
        String key = "uin_str" + type;
        int keyIndex = target.indexOf(key);
        if (keyIndex == -1) return "";

        int startIndex = keyIndex + key.length();
        int digitStart = -1;
        for (int i = startIndex; i < target.length(); i++) {
            if (Character.isDigit(target.charAt(i))) {
                digitStart = i;
                break;
            }
        }
        if (digitStart == -1) return "";

        int colonIdx = target.indexOf(':', digitStart);
        int realEnd = (colonIdx != -1) ? colonIdx : target.length();

        String raw = target.substring(digitStart, realEnd).trim();
        int end = 0;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        return raw.substring(0, end);
    }

    private static String extractToUinFromField7(byte[] data) {
        if (data == null) return "";
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2) {
                byte[] itemBytes = reader.readBytes();
                if (itemBytes == null) break;
                if (field == 7) {
                    String k = Proto.getString(itemBytes, 1);
                    if ("uin_str2".equals(k)) {
                        String v = Proto.getString(itemBytes, 2);
                        if (!v.isEmpty()) return v;
                    }
                }
            } else if (wire == 0) {
                reader.readVarint();
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return "";
    }

    private static boolean isValidQQ(String input) {
        return input != null && input.matches("[1-9]\\d{4,12}");
    }

    private static class ProtoReader {
        private final byte[] data;
        private int pos;
        private final int limit;

        ProtoReader(byte[] data) {
            this.data = data;
            this.pos = 0;
            this.limit = data != null ? data.length : 0;
        }

        boolean hasMore() {
            return data != null && pos < limit;
        }

        void skip(int n) {
            if (n > 0) {
                pos = Math.min(limit, pos + n);
            }
        }

        long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            return result;
        }

        byte[] readBytes() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > limit) return null;
            byte[] dest = new byte[len];
            System.arraycopy(data, pos, dest, 0, len);
            pos += len;
            return dest;
        }
    }

    private static class Proto {
        static byte[] getBytes(byte[] data, int targetField) {
            if (data == null) return null;
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasMore()) {
                long tag = reader.readVarint();
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    reader.readVarint();
                } else if (wire == 1) {
                    reader.skip(8);
                } else if (wire == 2) {
                    byte[] sub = reader.readBytes();
                    if (sub == null) break;
                    if (field == targetField) return sub;
                } else if (wire == 5) {
                    reader.skip(4);
                } else {
                    break;
                }
            }
            return null;
        }

        static String getString(byte[] data, int targetField) {
            byte[] b = getBytes(data, targetField);
            return b != null ? new String(b, StandardCharsets.UTF_8) : "";
        }

        static long getVarint(byte[] data, int targetField) {
            if (data == null) return 0;
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasMore()) {
                long tag = reader.readVarint();
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    long val = reader.readVarint();
                    if (field == targetField) return val;
                } else if (wire == 1) {
                    reader.skip(8);
                } else if (wire == 2) {
                    int len = (int) reader.readVarint();
                    if (len < 0) break;
                    reader.skip(len);
                } else if (wire == 5) {
                    reader.skip(4);
                } else {
                    break;
                }
            }
            return 0;
        }
    }
}
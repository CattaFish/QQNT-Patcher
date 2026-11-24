package com.tencent.qqnt.patch.modules.chat;

import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement;
import com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AntiRevokeModule implements IPatchModule {

    private static final String TAG = "AntiRevoke";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    private static final String CMD_SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush";

    private static final long BUSI_ID_C2C = 2021L;
    private static final long BUSI_ID_GROUP = 2022L;

    // 0x857 通知总线中，群消息撤回的专属枚举值 (opt_enum_type == 7)
    private static final int ENUM_TYPE_GROUP_RECALL = 7;

    @Override public String getId() { return "anti_revoke"; }
    @Override public String getName() { return "消息防撤回"; }
    @Override public boolean defaultEnabled() { return false; }

    private static final Set<String> sRevokedCache = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    @Override
    public void onRecvMsg(List<MsgRecord> msgList) {}

    @Override
    public byte[] onMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) {
        if (cmd == null || buf == null) return buf;

        // 1. 在线实时推送 (MsgPush)
        if (CMD_MSG_PUSH.equals(cmd)) {
            int revokeType = checkRevokeType(buf);
            if (revokeType != 0) {
                try {
                    // 仅当确凿拦截到他人的撤回并生成灰条时才拦截（返回 null）
                    // 凡是非撤回（如文件通知、状态变更）、自身撤回、校验不通过，一律放行原包（返回 buf）
                    boolean intercepted = processRecall(session, buf, revokeType);
                    if (intercepted) {
                        return null;
                    }
                } catch (Throwable t) {
                    PLog.e(TAG, "处理实时撤回异常", t);
                }
            }
            return buf;
        }

        // 2. 漫游离线同步 (InfoSyncPush)
        if (CMD_SYNC_PUSH.equals(cmd)) {
            if (hasRecallSignature(buf)) {
                return filterProtoTree(buf);
            }
            return buf;
        }

        return buf;
    }

    private static int checkRevokeType(byte[] buf) {
        if (buf == null || buf.length < 5) return 0;
        int limit = Math.min(buf.length - 4, 150);
        for (int i = 0; i < limit; i++) {
            if (buf[i] == 0x08) {
                // 0x08 0xDC 0x05 0x10 0x11: 群系统推送 0x2DC (732) 的子命令 0x11 (17)
                if ((buf[i + 1] & 0xFF) == 0xDC && (buf[i + 2] & 0xFF) == 0x05
                        && (buf[i + 3] & 0xFF) == 0x10 && (buf[i + 4] & 0xFF) == 0x11) {
                    return 1;
                }
                // 0x08 0x90 0x04 0x10 0x8A 0x01: C2C系统推送 0x210 (528) 的子命令 0x8A (138)
                if (i + 5 < buf.length && (buf[i + 1] & 0xFF) == 0x90 && (buf[i + 2] & 0xFF) == 0x04
                        && (buf[i + 3] & 0xFF) == 0x10 && (buf[i + 4] & 0xFF) == 0x8A && (buf[i + 5] & 0xFF) == 0x01) {
                    return 2;
                }
            }
        }
        return 0;
    }

    private static boolean hasRecallSignature(byte[] data) {
        if (data == null || data.length < 5) return false;
        int len = data.length;
        for (int i = 0; i <= len - 5; i++) {
            if (data[i] == 0x08) {
                if ((data[i + 1] & 0xFF) == 0xDC && (data[i + 2] & 0xFF) == 0x05
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x11) {
                    return true;
                }
                if (i + 5 < len && (data[i + 1] & 0xFF) == 0x90 && (data[i + 2] & 0xFF) == 0x04
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x8A && (data[i + 5] & 0xFF) == 0x01) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBytesSequence(byte[] data, byte[] seq) {
        if (data == null || seq == null || data.length < seq.length) return false;
        int limit = data.length - seq.length;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < seq.length; j++) {
                if (data[i + j] != seq[j]) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    private static int findSequence(byte[] data, byte[] seq) {
        if (data == null || seq == null || data.length < seq.length) return -1;
        int limit = data.length - seq.length;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < seq.length; j++) {
                if (data[i + j] != seq[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    // 查库预检核心：查本地消息是否存在
    private static boolean queryMsgExists(String peerUin, int istroop, long msgSeq) {
        if (peerUin == null || peerUin.isEmpty() || msgSeq <= 0) return false;
        try {
            Class<?> facadeClz = Class.forName("com.tencent.mobileqq.msg.api.IMessageFacade");
            Object facade = QRoute.api((Class) facadeClz);
            if (facade != null) {
                Method queryM = facade.getClass().getMethod("queryMsgItemByShmsgseq", String.class, int.class, long.class, long.class);
                List<?> list = (List<?>) queryM.invoke(facade, peerUin, istroop, msgSeq, 0L);
                return list != null && !list.isEmpty();
            }
        } catch (Throwable t) {
            PLog.d(TAG, "queryMsgExists 忽略异常: " + t.getMessage());
        }
        return false;
    }

    private static class RecallMeta {
        String peerUin;
        int istroop; // 1 为群聊, 0 为私聊
        long msgSeq;

        RecallMeta(String peerUin, int istroop, long msgSeq) {
            this.peerUin = peerUin;
            this.istroop = istroop;
            this.msgSeq = msgSeq;
        }
    }

    private static RecallMeta extractGroupRecallMeta(byte[] data) {
        if (data == null) return null;
        try {
            int idx = findSequence(data, new byte[]{0x08, 0x07});
            if (idx < 0) return null;

            byte[] notifyBody = Proto.subArray(data, idx);
            long optEnumType = Proto.getVarint(notifyBody, 1);
            if (optEnumType != ENUM_TYPE_GROUP_RECALL) return null;

            long groupCode = Proto.getVarint(notifyBody, 4);
            byte[] tag11 = Proto.getBytes(notifyBody, 11);
            if (tag11 == null) return null;

            byte[] tag3 = Proto.getBytes(tag11, 3);
            if (tag3 == null) return null;

            long seq = Proto.getVarint(tag3, 1);
            if (groupCode > 10000L && seq > 0) {
                return new RecallMeta(String.valueOf(groupCode), 1, seq);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static RecallMeta extractC2CRecallMeta(byte[] data) {
        if (data == null) return null;
        try {
            int idx = findSequence(data, new byte[]{0x08, (byte) 0x90, 0x04, 0x10, (byte) 0x8A, 0x01});
            if (idx < 0) return null;

            byte[] afterSig = Proto.subArray(data, idx);
            ProtoReader reader = new ProtoReader(afterSig);
            while (reader.hasMore()) {
                long tag = reader.readVarint();
                int wire = (int) (tag & 7);
                int fn = (int) (tag >>> 3);
                if (wire == 2) {
                    byte[] sub = reader.readBytes();
                    if (sub == null) break;
                    if (fn == 1) { // msg_info
                        long fromUin = Proto.getVarint(sub, 1);
                        long toUin = Proto.getVarint(sub, 2);
                        long seq = Proto.getVarint(sub, 3);
                        if (seq > 0 && (fromUin > 10000L || toUin > 10000L)) {
                            String myUin = AppContext.getCurrentUin();
                            String peer = (myUin != null && myUin.equals(String.valueOf(fromUin)))
                                    ? String.valueOf(toUin)
                                    : String.valueOf(fromUin);
                            return new RecallMeta(peer, 0, seq);
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
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isDirectRecallNode(byte[] data) {
        if (data == null || data.length < 5) return false;
        int limit = Math.min(data.length - 4, 60);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0x08) {
                if ((data[i + 1] & 0xFF) == 0xDC && (data[i + 2] & 0xFF) == 0x05
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x11) {
                    return hasBytesSequence(data, new byte[]{0x08, 0x07});
                }
                if (i + 5 < data.length && (data[i + 1] & 0xFF) == 0x90 && (data[i + 2] & 0xFF) == 0x04
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x8A && (data[i + 5] & 0xFF) == 0x01) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判定漫游同步中的撤回节点是否应该被过滤抹除：
     * 只有当本地确实存在原消息时，才过滤抹除撤回事件以保全原消息；
     * 若本地没有原消息（离线期间被撤回），放行让官方原生画灰条。
     */
    private static boolean shouldDropRecallNodeInSync(byte[] sub) {
        if (sub == null || sub.length < 5) return false;

        // 1. 群聊撤回节点预检
        if (hasBytesSequence(sub, new byte[]{0x08, (byte) 0xDC, 0x05, 0x10, 0x11})
                && hasBytesSequence(sub, new byte[]{0x08, 0x07})) {
            RecallMeta meta = extractGroupRecallMeta(sub);
            if (meta != null && meta.msgSeq > 0 && !meta.peerUin.isEmpty()) {
                boolean exists = queryMsgExists(meta.peerUin, 1, meta.msgSeq);
                if (exists) {
                    PLog.i(TAG, "漫游同步命中本地原消息 (group=" + meta.peerUin + ", seq=" + meta.msgSeq + ")，拦截撤回指令保护原消息");
                    return true;
                } else {
                    PLog.i(TAG, "漫游同步未命中本地原消息 (group=" + meta.peerUin + ", seq=" + meta.msgSeq + ")，放行撤回指令让官方画原生灰条");
                    return false;
                }
            }
        }

        // 2. 私聊撤回节点预检
        if (hasBytesSequence(sub, new byte[]{0x08, (byte) 0x90, 0x04, 0x10, (byte) 0x8A, 0x01})) {
            RecallMeta meta = extractC2CRecallMeta(sub);
            if (meta != null && meta.msgSeq > 0 && !meta.peerUin.isEmpty()) {
                boolean exists = queryMsgExists(meta.peerUin, 0, meta.msgSeq);
                if (exists) {
                    PLog.i(TAG, "漫游同步命中本地私聊消息 (peer=" + meta.peerUin + ", seq=" + meta.msgSeq + ")，拦截撤回指令保护原消息");
                    return true;
                } else {
                    PLog.i(TAG, "漫游同步未命中本地私聊消息 (peer=" + meta.peerUin + ", seq=" + meta.msgSeq + ")，放行撤回指令让官方画原生灰条");
                    return false;
                }
            }
        }

        return false;
    }

    private static byte[] filterProtoTree(byte[] data) {
        if (data == null || data.length == 0 || !hasRecallSignature(data)) return data;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ProtoReader reader = new ProtoReader(data);

            while (reader.hasMore()) {
                int start = reader.getPos();
                long tag = reader.readVarint();
                int wire = (int) (tag & 7);

                if (wire == 2) {
                    int l = (int) reader.readVarint();
                    int subStart = reader.getPos();
                    if (l >= 0 && subStart + l <= data.length) {
                        byte[] sub = Proto.subArray(data, subStart, l);
                        reader.skip(l);

                        if (shouldDropRecallNodeInSync(sub)) {
                            continue;
                        }

                        if (isDirectRecallNode(sub)) {
                            Proto.writeVarint(out, tag);
                            Proto.writeVarint(out, sub.length);
                            out.write(sub);
                            continue;
                        }

                        if (hasRecallSignature(sub)) {
                            byte[] cleanedSub = filterProtoTree(sub);
                            Proto.writeVarint(out, tag);
                            Proto.writeVarint(out, cleanedSub.length);
                            out.write(cleanedSub);
                        } else {
                            out.write(data, start, reader.getPos() - start);
                        }
                    } else {
                        break;
                    }
                } else if (wire == 0) {
                    reader.readVarint();
                    out.write(data, start, reader.getPos() - start);
                } else if (wire == 1) {
                    reader.skip(8);
                    out.write(data, start, reader.getPos() - start);
                } else if (wire == 5) {
                    reader.skip(4);
                    out.write(data, start, reader.getPos() - start);
                } else {
                    break;
                }
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return data;
        }
    }

    /**
     * 解析撤回通知并实施拦截与灰条构建
     * @return true 代表确为他人发起的合法撤回，已成功拦截并生成灰条；
     *         false 代表非撤回通知、本人主动撤回或解析不合法，放行原包。
     */
    private static boolean processRecall(IQQNTWrapperSession session, byte[] buf, int revokeType) {
        if (session == null) return false;
        IKernelMsgService msgService = session.getMsgService();
        if (msgService == null) return false;

        byte[] qqMsgBytes = Proto.getBytes(buf, 1);
        if (qqMsgBytes == null) return false;

        byte[] headerBytes = Proto.getBytes(qqMsgBytes, 1);
        if (headerBytes == null) return false;

        byte[] bodyBytes = Proto.getBytes(qqMsgBytes, 3);
        byte[] opBytes = bodyBytes != null ? Proto.getBytes(bodyBytes, 2) : null;
        if (opBytes == null || opBytes.length == 0) return false;

        String selfUid = Proto.getString(headerBytes, 6);
        String myUin = AppContext.getCurrentUin();

        if (revokeType == 1) {
            byte[] opBytesSkip7 = (opBytes.length > 7) ? Proto.subArray(opBytes, 7) : opBytes;

            // 校验 NotifyMsgBody 的 opt_enum_type (Tag 1) 必须等于 7 (群撤回)
            long optEnumType = Proto.getVarint(opBytesSkip7, 1);
            if (optEnumType != ENUM_TYPE_GROUP_RECALL) {
                return false;
            }

            // 必须包含真实的 opt_msg_recall 结构体 (Tag 11)
            byte[] tag11 = Proto.getBytes(opBytesSkip7, 11);
            if (tag11 == null || tag11.length == 0) {
                return false;
            }

            // Tag 1: operatorUid (操作撤回的人)
            String operatorUid = Proto.getString(tag11, 1);

            // Tag 3: recalled_msg_list (MessageMeta)
            byte[] tag3 = Proto.getBytes(tag11, 3);
            if (tag3 == null || tag3.length == 0) {
                return false;
            }

            // MessageMeta: Tag 1 为 seq (被撤回消息序号), Tag 6 为原发送人 UID
            long msgSeq = Proto.getVarint(tag3, 1);
            String senderUid = Proto.getString(tag3, 6);
            if (senderUid == null || senderUid.isEmpty()) {
                senderUid = operatorUid;
            }

            if (msgSeq <= 0 || operatorUid.isEmpty()) {
                return false;
            }

            // 本人主动撤回放行
            if (selfUid != null && !selfUid.isEmpty() && selfUid.equals(operatorUid)) {
                PLog.d(TAG, "本人主动执行群撤回操作，放行原包");
                return false;
            }

            // 提取群号
            String groupCode = "";
            long g4 = Proto.getVarint(opBytesSkip7, 4);
            if (g4 > 10000L) {
                groupCode = String.valueOf(g4);
            } else {
                String gCodeStr = Proto.getString(headerBytes, 2);
                if (gCodeStr != null && gCodeStr.matches("\\d+")) {
                    groupCode = gCodeStr;
                } else {
                    long gCodeVal = Proto.getVarint(headerBytes, 1);
                    if (gCodeVal > 10000L) groupCode = String.valueOf(gCodeVal);
                }
            }

            if (groupCode.isEmpty()) {
                return false;
            }

            // 防重判定
            String cacheKey = "grp_" + groupCode + "_seq_" + msgSeq;
            if (!sRevokedCache.add(cacheKey)) {
                return true;
            }

            String opUin = getUin(operatorUid);
            String opNick = getUserNickName(operatorUid, opUin);

            String json;
            if (!senderUid.isEmpty() && !senderUid.equals(operatorUid)) {
                boolean isSenderSelf = (selfUid != null && !selfUid.isEmpty() && selfUid.equals(senderUid));
                String senderUin = getUin(senderUid);
                if (!isSenderSelf && myUin != null && !myUin.isEmpty() && myUin.equals(senderUin)) {
                    isSenderSelf = true;
                }

                String senderNick = isSenderSelf ? "我" : getUserNickName(senderUid, senderUin);
                PLog.i(TAG, "拦截[管理代撤回]: 群=" + groupCode + ", 管理=" + opNick + ", 成员=" + senderNick + ", seq=" + msgSeq);
                json = buildGroupAdminRecallJson(operatorUid, opUin, opNick, senderUid, senderUin, senderNick, msgSeq, isSenderSelf);
            } else {
                PLog.i(TAG, "拦截[群成员撤回]: 群=" + groupCode + ", 人员=" + opNick + ", seq=" + msgSeq);
                json = buildGroupClickableJson(operatorUid, opUin, opNick, msgSeq);
            }

            Contact contact = new Contact(2, groupCode, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_GROUP, json, "", false, null);
            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);
            return true;

        } else if (revokeType == 2) {
            String operatorUid = "";
            long msgSeq = 0L;

            byte[] tag1 = Proto.getBytes(opBytes, 1);
            if (tag1 != null) {
                operatorUid = Proto.getString(tag1, 1);
                msgSeq = Proto.getVarint(tag1, 20);
            }
            if (operatorUid.isEmpty()) operatorUid = Proto.getString(headerBytes, 2);

            if (msgSeq <= 0 || operatorUid.isEmpty()) {
                return false;
            }

            if (selfUid != null && !selfUid.isEmpty() && selfUid.equals(operatorUid)) {
                PLog.d(TAG, "本人主动执行私聊撤回操作，放行原包");
                return false;
            }

            String cacheKey = "c2c_" + operatorUid + "_seq_" + msgSeq;
            if (!sRevokedCache.add(cacheKey)) {
                return true;
            }

            PLog.i(TAG, "拦截[私聊撤回]: 对方=" + operatorUid + ", seq=" + msgSeq);
            String json = buildC2CClickableJson(msgSeq);

            Contact contact = new Contact(1, operatorUid, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_C2C, json, "", false, null);
            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);
            return true;
        }
        return false;
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

    private static String getUserNickName(String uid, String uin) {
        if (uid == null || uid.isEmpty()) return "群成员";
        try {
            IFriendsInfoService service = QRoute.api(IFriendsInfoService.class);
            if (service != null) {
                String remark = service.getRemarkWithUid(uid, "");
                if (remark != null && !remark.isEmpty()) return remark;
                String nick = service.getNickWithUid(uid, "");
                if (nick != null && !nick.isEmpty()) return nick;
            }
        } catch (Throwable ignored) {}
        return (uin != null && !uin.isEmpty() && !uin.equals("0")) ? uin : "群成员";
    }

    private static String buildGroupClickableJson(String operatorUid, String uin, String nickName, long msgSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");
        String displayNick = escapeJson(nickName);
        String uinVal = (uin != null) ? uin : "";

        sb.append("{\"col\":\"3\",\"jp\":\"").append(operatorUid).append("\",\"nm\":\"").append(displayNick)
          .append("\",\"tp\":\"0\",\"type\":\"qq\",\"uid\":\"").append(operatorUid).append("\",\"uin\":\"").append(uinVal).append("\"},");
        sb.append("{\"txt\":\" 尝试撤回 \",\"type\":\"nor\"},");
        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        sb.append("\"param\":{\"seq\":\"").append(msgSeq).append("\"},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static String buildGroupAdminRecallJson(String opUid, String opUin, String opNick,
                                                    String senderUid, String senderUin, String senderNick,
                                                    long msgSeq, boolean isSenderSelf) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");

        sb.append("{\"col\":\"3\",\"jp\":\"").append(opUid).append("\",\"nm\":\"").append(escapeJson(opNick))
          .append("\",\"tp\":\"0\",\"type\":\"qq\",\"uid\":\"").append(opUid).append("\",\"uin\":\"").append(opUin != null ? opUin : "").append("\"},");
        sb.append("{\"txt\":\" 尝试撤回 \",\"type\":\"nor\"},");

        if (isSenderSelf) {
            sb.append("{\"txt\":\"我\",\"type\":\"nor\"},");
        } else {
            sb.append("{\"col\":\"3\",\"jp\":\"").append(senderUid).append("\",\"nm\":\"").append(escapeJson(senderNick))
              .append("\",\"tp\":\"0\",\"type\":\"qq\",\"uid\":\"").append(senderUid).append("\",\"uin\":\"").append(senderUin != null ? senderUin : "").append("\"},");
        }
        sb.append("{\"txt\":\" 的 \",\"type\":\"nor\"},");

        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        sb.append("\"param\":{\"seq\":\"").append(msgSeq).append("\"},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static String buildC2CClickableJson(long msgSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");
        sb.append("{\"txt\":\"对方 尝试撤回 \",\"type\":\"nor\"},");
        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        sb.append("\"param\":{\"seq\":\"").append(msgSeq).append("\"},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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

        int getPos() {
            return pos;
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
        static void writeVarint(ByteArrayOutputStream out, long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) (value & 0x7F));
        }

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

        static byte[] subArray(byte[] src, int start) {
            if (src == null || start < 0 || start >= src.length) return new byte[0];
            int length = src.length - start;
            byte[] dest = new byte[length];
            System.arraycopy(src, start, dest, 0, length);
            return dest;
        }

        static byte[] subArray(byte[] src, int start, int length) {
            if (src == null || start < 0 || length <= 0 || start + length > src.length) return new byte[0];
            byte[] dest = new byte[length];
            System.arraycopy(src, start, dest, 0, length);
            return dest;
        }
    }
}
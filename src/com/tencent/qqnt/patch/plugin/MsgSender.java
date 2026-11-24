package com.tencent.qqnt.patch.plugin;

import android.util.Log;
import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MsgSender {

    private static final String TAG = "QQ_DEBUG";
    private static volatile IQQNTWrapperSession sSession = null;
    private static volatile IKernelMsgService sMsgService = null;

    public static void setSession(IQQNTWrapperSession session) {
        if (session != null) {
            sSession = session;
            try {
                sMsgService = session.getMsgService();
            } catch (Throwable ignored) {}
        }
    }

    public static IKernelMsgService getMsgService() {
        if (sMsgService != null) return sMsgService;
        if (sSession != null) {
            try {
                sMsgService = sSession.getMsgService();
                if (sMsgService != null) return sMsgService;
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> kernelServiceClz = Class.forName("com.tencent.qqnt.kernel.api.IKernelService");
            Object kernelService = qrouteClz.getMethod("api", Class.class).invoke(null, kernelServiceClz);
            if (kernelService != null) {
                Object wrapper = kernelService.getClass().getMethod("getWrapperSession").invoke(kernelService);
                if (wrapper instanceof IQQNTWrapperSession) {
                    sSession = (IQQNTWrapperSession) wrapper;
                    sMsgService = sSession.getMsgService();
                }
            }
        } catch (Throwable ignored) {}
        return sMsgService;
    }

    public static String getMyUin() {
        return com.tencent.qqnt.patch.AppContext.getCurrentUin();
    }

    public static void sendMsg(String peerUin, String content, int chatType) {
        if (peerUin == null || content == null) return;
        Contact contact = makeContact(peerUin, chatType);
        sendMsg(contact, content);
    }

    public static void sendMsg(Contact contact, String content) {
        if (contact == null || content == null) return;
        ArrayList<MsgElement> elements = buildElements(contact, content);
        sendRawElements(contact, elements);
    }

    public static void sendPtt(String peerUin, String path, int chatType) {
        sendPtt(peerUin, path, chatType, 0);
    }

    public static void sendPtt(String peerUin, String path, int chatType, int durationMs) {
        sendPtt(makeContact(peerUin, chatType), path, durationMs);
    }

    public static void sendPtt(Contact contact, String path) {
        sendPtt(contact, path, 0);
    }

    public static void sendPtt(Contact contact, String path, int durationMs) {
        if (contact == null || path == null) return;
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil == null) return;

            int ms = durationMs > 0 ? durationMs : estimatePttDurationMs(path);
            ArrayList<Byte> wave = new ArrayList<>();
            for (int i = 0; i < 20; i++) wave.add((byte) 20);

            Method m = msgUtil.getClass().getMethod("createPttElement", String.class, int.class, ArrayList.class);
            Object pttElem = m.invoke(msgUtil, path, ms, wave);

            ArrayList<Object> list = new ArrayList<>();
            list.add(pttElem);
            sendRawElements(contact, list);
        } catch (Throwable t) {
            Log.e(TAG, "[MsgSender] sendPtt 异常", t);
        }
    }

    public static void sendReplyMsg(String peerUin, long replyMsgId, String content, int chatType) {
        sendReplyMsg(makeContact(peerUin, chatType), replyMsgId, content);
    }

    public static void sendReplyMsg(Contact contact, long replyMsgId, String content) {
        if (contact == null || content == null) return;
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil == null) return;

            Method createReply = msgUtil.getClass().getMethod("createReplyElement", long.class);
            Object replyElem = createReply.invoke(msgUtil, replyMsgId);

            ArrayList<Object> elements = new ArrayList<>();
            elements.add(replyElem);
            elements.addAll(buildElements(contact, content));

            sendRawElements(contact, elements);
        } catch (Throwable t) {
            Log.e(TAG, "[MsgSender] sendReplyMsg 异常", t);
        }
    }

    public static void sendCard(String peerUin, String jsonStr, int chatType) {
        sendCard(makeContact(peerUin, chatType), jsonStr);
    }

    public static void sendCard(Contact contact, String jsonStr) {
        if (contact == null || jsonStr == null) return;
        try {
            Class<?> elemClz = Class.forName("com.tencent.qqnt.kernel.nativeinterface.MsgElement");
            Object elem = elemClz.getDeclaredConstructor().newInstance();
            elemClz.getField("elementType").set(elem, 10);

            Class<?> arkClz = Class.forName("com.tencent.qqnt.kernel.nativeinterface.ArkElement");
            Object arkObj = arkClz.getDeclaredConstructor().newInstance();
            arkClz.getField("bytesData").set(arkObj, jsonStr);
            elemClz.getField("arkElement").set(elem, arkObj);

            ArrayList<Object> list = new ArrayList<>();
            list.add(elem);
            sendRawElements(contact, list);
        } catch (Throwable t) {
            Log.e(TAG, "[MsgSender] sendCard 异常", t);
        }
    }

    public static void sendVideo(String peerUin, String path, int chatType) {
        sendVideo(makeContact(peerUin, chatType), path);
    }

    public static void sendVideo(Contact contact, String path) {
        if (contact == null || path == null) return;
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createVideoElement", String.class);
                Object elem = m.invoke(msgUtil, path);
                ArrayList<Object> list = new ArrayList<>();
                list.add(elem);
                sendRawElements(contact, list);
            }
        } catch (Throwable ignored) {}
    }

    public static void sendFile(String peerUin, String path, int chatType) {
        sendFile(makeContact(peerUin, chatType), path);
    }

    public static void sendFile(Contact contact, String path) {
        if (contact == null || path == null) return;
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createFileElement", String.class);
                Object elem = m.invoke(msgUtil, path);
                ArrayList<Object> list = new ArrayList<>();
                list.add(elem);
                sendRawElements(contact, list);
            }
        } catch (Throwable ignored) {}
    }

    public static void sendPai(String toUin, String peerUin, int chatType) {
        try {
            Object runtime = com.tencent.qqnt.patch.AppContext.getAppRuntime();
            if (runtime == null) return;
            Method getHandler = runtime.getClass().getMethod("getBusinessHandler", String.class);
            Object handler = getHandler.invoke(runtime, "com.tencent.mobileqq.paiyipai.PaiYiPaiHandler");
            if (handler == null) return;

            for (Method m : handler.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == String.class && p[1] == String.class) {
                    m.invoke(handler, toUin, peerUin, chatType, 1);
                    return;
                } else if (p.length == 4 && p[0] == int.class && p[1] == int.class) {
                    m.invoke(handler, chatType, 1, toUin, peerUin);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void recall(int chatType, String peerUin, long msgId) {
        recall(makeContact(peerUin, chatType), msgId);
    }

    public static void recall(Contact contact, long msgId) {
        IKernelMsgService service = getMsgService();
        if (service == null || contact == null) return;
        try {
            ArrayList<Long> ids = new ArrayList<>();
            ids.add(msgId);
            for (Method m : service.getClass().getMethods()) {
                if ("recallMsg".equals(m.getName()) && m.getParameterTypes().length >= 2) {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 3) {
                        m.invoke(service, contact, ids, null);
                    } else {
                        m.invoke(service, contact, ids);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void sendRawElements(Contact contact, ArrayList<?> elements) {
        IKernelMsgService service = getMsgService();
        if (service == null || contact == null || elements == null || elements.isEmpty()) return;
        try {
            long msgId = 0L;
            try {
                Method genMethod = service.getClass().getMethod("generateMsgUniqueId", int.class, long.class);
                msgId = (Long) genMethod.invoke(service, contact.getChatType(), System.currentTimeMillis());
            } catch (Throwable ignored) {}

            for (Method m : service.getClass().getMethods()) {
                if ("sendMsg".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 5) {
                        m.invoke(service, msgId, contact, elements, new HashMap(), null);
                    } else {
                        m.invoke(service, msgId, contact, elements, new HashMap());
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static ArrayList<MsgElement> buildElements(Contact contact, String content) {
        ArrayList<MsgElement> elements = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[(atUin|pic)=([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(content);
        int lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                elements.add(createTextElement(content.substring(lastIndex, matcher.start())));
            }
            String tag = matcher.group(1);
            String val = matcher.group(2);

            if ("atUin".equals(tag) && contact.getChatType() == 2) {
                elements.add(createAtElement(val));
            } else if ("pic".equals(tag)) {
                MsgElement picElem = createPicElement(val);
                if (picElem != null) elements.add(picElem);
            }
            lastIndex = matcher.end();
        }

        if (lastIndex < content.length()) {
            elements.add(createTextElement(content.substring(lastIndex)));
        }
        return elements;
    }

    private static MsgElement createTextElement(String text) {
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createTextElement", String.class);
                return (MsgElement) m.invoke(msgUtil, text);
            }
        } catch (Throwable ignored) {}

        MsgElement elem = new MsgElement();
        elem.elementType = 1;
        elem.textElement = new TextElement();
        elem.textElement.content = text;
        return elem;
    }

    private static MsgElement createAtElement(String uin) {
        String uid = getUidFromUin(uin);
        int atType = "0".equals(uin) ? 1 : 2;
        String display = "0".equals(uin) ? "@全体成员" : ("@" + uin + " ");
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createAtTextElement", String.class, String.class, int.class);
                return (MsgElement) m.invoke(msgUtil, display, uid, atType);
            }
        } catch (Throwable ignored) {}

        MsgElement elem = new MsgElement();
        elem.elementType = 1;
        elem.textElement = new TextElement();
        elem.textElement.content = display;
        elem.textElement.atType = atType;
        elem.textElement.atNtUid = uid;
        return elem;
    }

    private static MsgElement createPicElement(String path) {
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createPicElement", String.class, boolean.class, int.class);
                return (MsgElement) m.invoke(msgUtil, path, true, 0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int estimatePttDurationMs(String path) {
        try {
            File file = new File(path);
            if (!file.exists() || file.length() <= 0) return 1000;
            byte[] bytes = new byte[(int) Math.min(file.length(), 65536)];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }
            int offset = 0;
            if (bytes.length >= 10 && new String(bytes, 1, 9, StandardCharsets.US_ASCII).equals("#!SILK_V3")) {
                offset = 10;
            } else if (bytes.length >= 9 && new String(bytes, 0, 9, StandardCharsets.US_ASCII).equals("#!SILK_V3")) {
                offset = 9;
            } else {
                return 1000;
            }
            int frames = 0;
            while (offset + 2 <= bytes.length) {
                int frameLen = (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
                offset += 2;
                if (frameLen <= 0 || frameLen > 4096 || offset + frameLen > bytes.length) break;
                offset += frameLen;
                frames++;
            }
            return frames > 0 ? frames * 20 : 1000;
        } catch (Throwable t) {
            return 1000;
        }
    }

    public static Contact makeContact(String target, int chatType) {
        String peerUid = target;
        if ((chatType == 1 || chatType == 100) && !target.startsWith("u_")) {
            try {
                IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
                if (api != null) {
                    String u = api.getUidFromUin(target);
                    if (u != null && !u.isEmpty()) peerUid = u;
                }
            } catch (Throwable ignored) {}
        }
        return new Contact(chatType, peerUid, "");
    }

    public static String getUidFromUin(String uin) {
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                return api.getUidFromUin(uin);
            }
        } catch (Throwable ignored) {}
        return uin;
    }

    public static String getUinFromUid(String uid) {
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                return api.getUinFromUid(uid);
            }
        } catch (Throwable ignored) {}
        return uid;
    }
}

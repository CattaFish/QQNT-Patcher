package com.tencent.qqnt.patch.plugin;

import com.tencent.qqnt.patch.AppContext;
import me.yxp.qfun.plugin.bean.ForbidInfo;
import me.yxp.qfun.plugin.bean.GroupInfo;
import me.yxp.qfun.plugin.bean.MemberInfo;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TroopHelper {

    public static Object getKernelGroupService() {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return null;
            Class<?> kernelServiceClz = Class.forName("com.tencent.qqnt.kernel.api.IKernelService");
            Method getRuntimeService = runtime.getClass().getMethod("getRuntimeService", Class.class, String.class);
            Object kernelService = getRuntimeService.invoke(runtime, kernelServiceClz, "");
            if (kernelService != null) {
                Object groupServiceWrapper = kernelService.getClass().getMethod("getGroupService").invoke(kernelService);
                if (groupServiceWrapper != null) {
                    return groupServiceWrapper.getClass().getMethod("getService").invoke(groupServiceWrapper);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static List<GroupInfo> getGroupList() {
        List<GroupInfo> result = new ArrayList<>();
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> troopRepoClz = Class.forName("com.tencent.qqnt.troop.ITroopListRepoApi");
            Object repo = qrouteClz.getMethod("api", Class.class).invoke(null, troopRepoClz);
            if (repo != null) {
                List<?> list = (List<?>) repo.getClass().getMethod("getSortedJoinedTroopInfoFromCache").invoke(repo);
                if (list != null) {
                    for (Object tInfo : list) {
                        Class<?> c = tInfo.getClass();
                        Field fUin = c.getField("troopuin");
                        Field fName = c.getField("troopNameFromNT");
                        Field fOwner = c.getField("troopowneruin");
                        String uin = String.valueOf(fUin.get(tInfo));
                        String name = fName.get(tInfo) != null ? String.valueOf(fName.get(tInfo)) : uin;
                        String owner = String.valueOf(fOwner.get(tInfo));
                        result.add(new GroupInfo(uin, name, owner, tInfo));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    public static Object getGroupInfo(String troopUin) {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return null;
            Class<?> clz = Class.forName("com.tencent.mobileqq.troop.api.ITroopInfoService");
            Method getRuntimeService = runtime.getClass().getMethod("getRuntimeService", Class.class, String.class);
            Object service = getRuntimeService.invoke(runtime, clz, "");
            if (service != null) {
                Method getInfo = service.getClass().getMethod("getTroopInfo", String.class);
                return getInfo.invoke(service, troopUin);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isShutUp(String troopUin) {
        try {
            Object info = getGroupInfo(troopUin);
            if (info == null) return false;
            Field fGag = info.getClass().getField("dwGagTimeStamp");
            Field fGagMe = info.getClass().getField("dwGagTimeStamp_me");
            return (fGag.getLong(info) != 0L || fGagMe.getLong(info) != 0L);
        } catch (Throwable ignored) {}
        return false;
    }

    public static List<MemberInfo> getGroupMemberList(String troopUin) {
        List<MemberInfo> result = new ArrayList<>();
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return result;

            long gCode = Long.parseLong(troopUin);
            CountDownLatch latch = new CountDownLatch(1);

            ClassLoader cl = groupService.getClass().getClassLoader();
            Class<?> cbClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback", true, cl);

            Object callbackProxy = Proxy.newProxyInstance(cl, new Class<?>[]{cbClass}, (proxy, method, args) -> {
                if ("onResult".equals(method.getName()) && args != null && args.length >= 3) {
                    Object resObj = args[2];
                    if (resObj != null) {
                        Field fInfos = resObj.getClass().getField("infos");
                        Map<?, ?> map = (Map<?, ?>) fInfos.get(resObj);
                        if (map != null) {
                            for (Object ntMem : map.values()) {
                                Class<?> mc = ntMem.getClass();
                                long joinTime = mc.getField("joinTime").getInt(ntMem);
                                long lastSpeak = mc.getField("lastSpeakTime").getInt(ntMem);
                                long uin = mc.getField("uin").getLong(ntMem);
                                int level = mc.getField("memberRealLevel").getInt(ntMem);
                                String card = (String) mc.getField("cardName").get(ntMem);
                                String nick = (String) mc.getField("nick").get(ntMem);
                                Object roleObj = mc.getField("role").get(ntMem);

                                String displayName = (card != null && !card.isEmpty()) ? card : (nick != null ? nick : "");
                                String roleStr = roleObj != null ? roleObj.toString() : "MEMBER";

                                result.add(new MemberInfo(joinTime, lastSpeak, String.valueOf(uin), level, displayName, roleStr, ntMem));
                            }
                        }
                    }
                    latch.countDown();
                }
                return null;
            });

            Method getAll = groupService.getClass().getMethod("getAllMemberList", long.class, boolean.class, cbClass);
            getAll.invoke(groupService, gCode, false, callbackProxy);

            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
        return result;
    }

    public static MemberInfo getMemberInfo(String troopUin, String memberUin) {
        List<MemberInfo> list = getGroupMemberList(troopUin);
        for (MemberInfo m : list) {
            if (memberUin.equals(m.uin)) return m;
        }
        return new MemberInfo(0, 0, memberUin, 0, memberUin, "MEMBER", null);
    }

    public static List<ForbidInfo> getProhibitList(String troopUin) {
        List<ForbidInfo> result = new ArrayList<>();
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return result;

            long gCode = Long.parseLong(troopUin);
            CountDownLatch latch = new CountDownLatch(1);

            ClassLoader cl = groupService.getClass().getClassLoader();
            Class<?> cbClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback", true, cl);

            Object callbackProxy = Proxy.newProxyInstance(cl, new Class<?>[]{cbClass}, (proxy, method, args) -> {
                if ("onResult".equals(method.getName()) && args != null && args.length >= 3) {
                    Object resObj = args[2];
                    if (resObj != null) {
                        Field fInfos = resObj.getClass().getField("infos");
                        Map<?, ?> map = (Map<?, ?>) fInfos.get(resObj);
                        if (map != null) {
                            long now = System.currentTimeMillis() / 1000;
                            for (Object ntMem : map.values()) {
                                Class<?> mc = ntMem.getClass();
                                int shutUpTime = mc.getField("shutUpTime").getInt(ntMem);
                                if (shutUpTime > now) {
                                    long uin = mc.getField("uin").getLong(ntMem);
                                    String card = (String) mc.getField("cardName").get(ntMem);
                                    String nick = (String) mc.getField("nick").get(ntMem);
                                    String name = (card != null && !card.isEmpty()) ? card : nick;
                                    result.add(new ForbidInfo(String.valueOf(uin), shutUpTime, shutUpTime - now, name));
                                }
                            }
                        }
                    }
                    latch.countDown();
                }
                return null;
            });

            Method getAll = groupService.getClass().getMethod("getAllMemberList", long.class, boolean.class, cbClass);
            getAll.invoke(groupService, gCode, false, callbackProxy);

            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
        return result;
    }

    public static void shutUp(String troopUin, String memberUin, long seconds) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);

            Class<?> shutUpInfoClz = Class.forName("com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo");
            Object info = shutUpInfoClz.getDeclaredConstructor().newInstance();
            shutUpInfoClz.getField("uid").set(info, uid);
            shutUpInfoClz.getField("timeStamp").set(info, (int) seconds);

            ArrayList<Object> list = new ArrayList<>();
            list.add(info);

            for (Method m : groupService.getClass().getMethods()) {
                if ("setMemberShutUp".equals(m.getName()) && m.getParameterTypes().length >= 3) {
                    m.invoke(groupService, gCode, list, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void shutUpAll(String troopUin, boolean enable) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            for (Method m : groupService.getClass().getMethods()) {
                if ("setGroupShutUp".equals(m.getName()) && m.getParameterTypes().length >= 3) {
                    m.invoke(groupService, gCode, enable, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void kickGroup(String troopUin, String memberUin, boolean block) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);
            ArrayList<String> uids = new ArrayList<>();
            uids.add(uid);

            for (Method m : groupService.getClass().getMethods()) {
                if ("kickMember".equals(m.getName()) && m.getParameterTypes().length >= 5) {
                    m.invoke(groupService, gCode, uids, block, "", null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void changeMemberName(String troopUin, String memberUin, String newCard) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);
            for (Method m : groupService.getClass().getMethods()) {
                if ("modifyMemberCardName".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                    m.invoke(groupService, gCode, uid, newCard, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void setGroupAdmin(String troopUin, String memberUin, boolean enable) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);

            Class roleEnumClz = Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole");
            Object role = Enum.valueOf(roleEnumClz, enable ? "ADMIN" : "MEMBER");

            for (Method m : groupService.getClass().getMethods()) {
                if ("modifyMemberRole".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                    m.invoke(groupService, gCode, uid, role, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void setGroupMemberTitle(String troopUin, String uin, String title) {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return;

            long gCode = Long.parseLong(troopUin);
            long memberUin = Long.parseLong(uin);

            ByteArrayOutputStream sub = new ByteArrayOutputStream();
            writeVarint(sub, (1 << 3) | 0);
            writeVarint(sub, gCode);
            writeVarint(sub, (3 << 3) | 0);
            writeVarint(sub, memberUin);
            writeVarint(sub, (5 << 3) | 2);
            byte[] titleBytes = title.getBytes("UTF-8");
            writeVarint(sub, titleBytes.length);
            sub.write(titleBytes);
            writeVarint(sub, (6 << 3) | 0);
            writeVarint(sub, 0xFFFFFFFFL);

            byte[] subBytes = sub.toByteArray();
            ByteArrayOutputStream root = new ByteArrayOutputStream();
            writeVarint(root, (1 << 3) | 0);
            writeVarint(root, 2300L);
            writeVarint(root, (2 << 3) | 0);
            writeVarint(root, 2L);
            writeVarint(root, (4 << 3) | 2);
            writeVarint(root, subBytes.length);
            root.write(subBytes);

            byte[] wupBuf = root.toByteArray();

            Class<?> toServiceMsgClz = Class.forName("com.tencent.qphone.base.remote.ToServiceMsg");
            Object msg = toServiceMsgClz.getConstructor(String.class, String.class, String.class)
                    .newInstance("mobileqq.service", MsgSender.getMyUin(), "OidbSvc.0x8fc_2");
            toServiceMsgClz.getMethod("putWupBuffer", byte[].class).invoke(msg, (Object) wupBuf);
            toServiceMsgClz.getMethod("addAttribute", String.class, Object.class).invoke(msg, "req_pb_protocol_flag", true);

            runtime.getClass().getMethod("sendToService", toServiceMsgClz).invoke(runtime, msg);
        } catch (Throwable ignored) {}
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    public static void clockIn(String troopUin) {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return;
            Method getHandler = runtime.getClass().getMethod("getBusinessHandler", String.class);
            Object handler = getHandler.invoke(runtime, "com.tencent.mobileqq.troop.clockin.handler.TroopClockInHandler");
            if (handler != null) {
                for (Method m : handler.getClass().getMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                        m.invoke(handler, troopUin, MsgSender.getMyUin());
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}

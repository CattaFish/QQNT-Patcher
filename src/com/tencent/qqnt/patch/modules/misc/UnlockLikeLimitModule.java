package com.tencent.qqnt.patch.modules.misc;

import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UnlockLikeLimitModule implements IPatchModule {

    private static final String TAG = "UnlockLikeLimit";
    public static final String FEATURE_ID = "unlock_like_limit";

    private static final int TARGET_DAILY_LIMIT = 50;
    private static final int MAX_SINGLE_PACKET_COUNT = 10;

    private static final ThreadLocal<Boolean> sInSplitSend = new ThreadLocal<>();
    private static final Map<Class<?>, Method> sSendMethodCache = new ConcurrentHashMap<>();
    private static final Map<String, Field> sFieldCache = new ConcurrentHashMap<>();
    private static volatile Method sIsFriendMethod;

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "突破非好友点赞上限";
    }

    @Override
    public String getCategory() {
        return CATEGORY_MISC;
    }

    @Override
    public String getSubName() {
        return "将非好友每日可点赞次数上限提升至 50 次";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled(FEATURE_ID, false);
    }

    /**
     * 资料卡回包解析前：仅对非好友提升 RespSummaryCard.bAvailVoteCnt 至 50 - 已赞数
     */
    public static void onSummaryCardResp(Object respSummaryCard, String targetUin) {
        if (!isModuleActive() || respSummaryCard == null) {
            return;
        }
        if (targetUin == null || targetUin.isEmpty() || targetUin.equals(getCurrentSelfUin())) {
            return;
        }
        if (isFriendUin(targetUin)) {
            return;
        }

        try {
            int ret0x6d4 = readIntField(respSummaryCard, "i0x6d4Success", 0);
            if (ret0x6d4 != 0) {
                return;
            }

            int haveVoted = Math.max(0, readIntField(respSummaryCard, "bHaveVotedCnt", 0));
            int origAvail = readIntField(respSummaryCard, "bAvailVoteCnt", -1);
            if (origAvail < 0) {
                return;
            }

            int targetAvail = Math.max(0, TARGET_DAILY_LIMIT - haveVoted);
            if (targetAvail > origAvail) {
                if (writeNumericField(respSummaryCard, "bAvailVoteCnt", targetAvail)) {
                    PLog.i(TAG, "非好友资料卡解锁点赞上限: uin=" + targetUin
                            + ", 已赞=" + haveVoted + ", 原剩余=" + origAvail + " -> 新剩余=" + targetAvail);
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "onSummaryCardResp 异常: " + t.getMessage());
        }
    }

    /**
     * 访客 / 谁赞过我列表解析前：仅对非好友提升 UserProfile.bAvailableCnt 至 50 - 今日已赞数
     */
    public static void onUserProfileRead(Object userProfile) {
        if (!isModuleActive() || userProfile == null) {
            return;
        }

        try {
            long targetUin = readLongField(userProfile, "lEctID", 0L);
            if (targetUin <= 0L) {
                return;
            }
            String uinStr = String.valueOf(targetUin);
            if (uinStr.equals(getCurrentSelfUin()) || isFriendUin(uinStr)) {
                return;
            }

            int closeNeighborVote = readIntField(userProfile, "bCloseNeighborVote", 0);
            int todayVoted = Math.max(0, readIntField(userProfile, "bTodayVotedCnt", 0));
            int origAvail = readIntField(userProfile, "bAvailableCnt", -1);
            if (origAvail < 0) {
                return;
            }

            // 对方关闭陌生人点赞时保持原状
            if (closeNeighborVote == 1) {
                return;
            }

            int targetAvail = Math.max(0, TARGET_DAILY_LIMIT - todayVoted);
            if (targetAvail > origAvail) {
                if (writeNumericField(userProfile, "bAvailableCnt", targetAvail)) {
                    PLog.d(TAG, "非好友访客列表解锁点赞上限: uin=" + uinStr
                            + ", 今日已赞=" + todayVoted + ", 原剩余=" + origAvail + " -> 新剩余=" + targetAvail);
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "onUserProfileRead 异常: " + t.getMessage());
        }
    }

    /**
     * 发包层自动切片：当单次聚合赞数超过 10 时，拆分为每包最多 10 赞连续发送
     *
     * @return true 表示已由模块分包发出，跳过原方法；false 表示放行原方法单包发出
     */
    public static boolean onSendFavorite(
            Object handlerObj,
            long selfUin,
            long targetUin,
            byte[] cookies,
            int favoriteSource,
            int count,
            int from,
            String methodName
    ) {
        if (!isModuleActive() || Boolean.TRUE.equals(sInSplitSend.get())) {
            return false;
        }
        if (handlerObj == null || count <= MAX_SINGLE_PACKET_COUNT) {
            return false;
        }

        Method sendMethod = resolveSendMethod(handlerObj.getClass(), methodName);
        if (sendMethod == null) {
            PLog.w(TAG, "未找到发赞方法: " + handlerObj.getClass().getName() + "->" + methodName);
            return false;
        }

        int sentPackets = 0;
        sInSplitSend.set(Boolean.TRUE);
        try {
            int remaining = count;
            while (remaining > 0) {
                int chunk = Math.min(remaining, MAX_SINGLE_PACKET_COUNT);
                sendMethod.invoke(handlerObj, selfUin, targetUin, cookies, favoriteSource, chunk, from);
                remaining -= chunk;
                sentPackets++;
            }
            PLog.i(TAG, "点赞自动分包发送: handler=" + handlerObj.getClass().getSimpleName()
                    + ", target=" + targetUin + ", total=" + count
                    + ", packets=" + sentPackets + ", src=" + favoriteSource);
        } catch (Throwable t) {
            PLog.e(TAG, "点赞分包发送异常", t);
        } finally {
            sInSplitSend.set(Boolean.FALSE);
        }

        return sentPackets > 0;
    }

    private static boolean isFriendUin(String uin) {
        try {
            IRelationNTUinAndUidApi uinApi = QRoute.api(IRelationNTUinAndUidApi.class);
            if (uinApi == null) return false;
            String uid = uinApi.getUidFromUin(uin);
            if (uid == null || uid.isEmpty()) {
                return false;
            }

            IFriendsInfoService friendsService = QRoute.api(IFriendsInfoService.class);
            if (friendsService == null) return false;

            Method m = sIsFriendMethod;
            if (m == null || !m.getDeclaringClass().isAssignableFrom(friendsService.getClass())) {
                m = friendsService.getClass().getMethod("isFriend", String.class, String.class);
                m.setAccessible(true);
                sIsFriendMethod = m;
            }
            Object res = m.invoke(friendsService, uid, TAG);
            return Boolean.TRUE.equals(res);
        } catch (Throwable t) {
            PLog.w(TAG, "查询好友关系异常: " + t.getMessage());
            return false;
        }
    }

    private static Method resolveSendMethod(Class<?> handlerClass, String methodName) {
        Method cached = sSendMethodCache.get(handlerClass);
        if (cached != null) {
            return cached;
        }

        Method found = null;
        if (methodName != null && !methodName.isEmpty()) {
            try {
                found = handlerClass.getDeclaredMethod(
                        methodName,
                        long.class, long.class, byte[].class, int.class, int.class, int.class
                );
            } catch (NoSuchMethodException ignored) {}
        }

        if (found == null) {
            for (Method m : handlerClass.getDeclaredMethods()) {
                if (m.getReturnType() != void.class) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 6
                        && pts[0] == long.class
                        && pts[1] == long.class
                        && pts[2] == byte[].class
                        && pts[3] == int.class
                        && pts[4] == int.class
                        && pts[5] == int.class) {
                    found = m;
                    break;
                }
            }
        }

        if (found != null) {
            found.setAccessible(true);
            sSendMethodCache.put(handlerClass, found);
        }
        return found;
    }

    private static String getCurrentSelfUin() {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return "";
            try {
                Method m = runtime.getClass().getMethod("getCurrentAccountUin");
                Object res = m.invoke(runtime);
                if (res != null) return String.valueOf(res);
            } catch (NoSuchMethodException ignored) {}
            Method m2 = runtime.getClass().getMethod("getAccount");
            Object res2 = m2.invoke(runtime);
            return res2 != null ? String.valueOf(res2) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Field resolveField(Class<?> clazz, String fieldName) {
        String key = clazz.getName() + "#" + fieldName;
        Field cached = sFieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                sFieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static int readIntField(Object target, String fieldName, int defVal) {
        try {
            Field f = resolveField(target.getClass(), fieldName);
            if (f != null) {
                Object val = f.get(target);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Throwable ignored) {}
        return defVal;
    }

    private static long readLongField(Object target, String fieldName, long defVal) {
        try {
            Field f = resolveField(target.getClass(), fieldName);
            if (f != null) {
                Object val = f.get(target);
                if (val instanceof Number) {
                    return ((Number) val).longValue();
                }
            }
        } catch (Throwable ignored) {}
        return defVal;
    }

    private static boolean writeNumericField(Object target, String fieldName, int value) {
        try {
            Field f = resolveField(target.getClass(), fieldName);
            if (f == null) return false;
            Class<?> type = f.getType();
            if (type == short.class || type == Short.class) {
                f.setShort(target, (short) value);
                return true;
            } else if (type == int.class || type == Integer.class) {
                f.setInt(target, value);
                return true;
            } else if (type == long.class || type == Long.class) {
                f.setLong(target, value);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
package com.tencent.qqnt.patch.modules.misc;

import android.widget.ImageView;

import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class OneClickLikeModule implements IPatchModule {

    private static final String TAG = "OneClickLike";
    public static final String FEATURE_ID = "one_click_like";

    private static final int MAX_SAFE_LOOP = 100;

    private static final ThreadLocal<Boolean> sInProfileVote = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> sInVisitorVote = new ThreadLocal<>();

    private static volatile Field sCardAvailVoteField;
    private static volatile Field sProfileAvailVoteField;
    private static volatile Method sProfileVoteMethod;
    private static volatile Method sVisitorVoteMethod;

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "一键点赞";
    }

    @Override
    public String getCategory() {
        return CATEGORY_MISC;
    }

    @Override
    public String getSubName() {
        return "点击资料卡或访客页点赞时自动点满当日可赞次数";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    public static boolean isModuleActive() {
        return ConfigManager.isModuleEnabled(FEATURE_ID, false);
    }

    /**
     * 他人资料卡点赞入口拦截
     *
     * @return true 表示已由模块接管完成，调用方直接 return；false 表示放行执行原方法
     */
    public static boolean onProfileCardVote(Object componentObj, Object cardObj, String methodName) {
        if (!isModuleActive() || Boolean.TRUE.equals(sInProfileVote.get())) {
            return false;
        }
        if (componentObj == null || cardObj == null) {
            return false;
        }

        long avail = readLongField(cardObj, "bAvailVoteCnt", true);
        if (avail <= 1L) {
            return false;
        }

        int targetCount = (int) Math.min(avail, MAX_SAFE_LOOP);

        Method voteMethod = resolveProfileVoteMethod(componentObj.getClass(), methodName, cardObj.getClass());
        if (voteMethod == null) {
            PLog.w(TAG, "未找到资料卡点赞方法: " + methodName);
            return false;
        }

        int executed = 0;
        sInProfileVote.set(Boolean.TRUE);
        try {
            for (int i = 0; i < targetCount; i++) {
                long before = readLongField(cardObj, "bAvailVoteCnt", true);
                if (before <= 0L) {
                    break;
                }
                voteMethod.invoke(componentObj, cardObj);
                executed++;
                long after = readLongField(cardObj, "bAvailVoteCnt", true);
                if (after >= before) {
                    break;
                }
            }
            if (executed > 0) {
                PLog.i(TAG, "资料卡一键点赞完成: 本次 " + executed + " 次, 剩余 " + readLongField(cardObj, "bAvailVoteCnt", true));
            }
        } catch (Throwable t) {
            PLog.e(TAG, "资料卡一键点赞异常", t);
        } finally {
            sInProfileVote.set(Boolean.FALSE);
        }

        return executed > 0;
    }

    /**
     * 访客 / 谁赞过我 / 我赞过谁 回赞入口拦截
     *
     * @return -1 表示放行执行原方法；0 表示已接管并返回 false；1 表示已接管并返回 true
     */
    public static int onVisitorVote(Object voteHelperObj, Object cardProfileObj, Object imageViewObj, String methodName) {
        if (!isModuleActive() || Boolean.TRUE.equals(sInVisitorVote.get())) {
            return -1;
        }
        if (voteHelperObj == null || cardProfileObj == null) {
            return -1;
        }

        long avail = readLongField(cardProfileObj, "bAvailableCnt", false);
        if (avail <= 1L) {
            return -1;
        }

        int targetCount = (int) Math.min(avail, MAX_SAFE_LOOP);

        Method voteMethod = resolveVisitorVoteMethod(voteHelperObj.getClass(), methodName, cardProfileObj.getClass());
        if (voteMethod == null) {
            PLog.w(TAG, "未找到访客回赞方法: " + methodName);
            return -1;
        }

        int executed = 0;
        int retCode = 0;
        sInVisitorVote.set(Boolean.TRUE);
        try {
            for (int i = 0; i < targetCount; i++) {
                long before = readLongField(cardProfileObj, "bAvailableCnt", false);
                if (before <= 0L) {
                    break;
                }
                Object res = voteMethod.invoke(voteHelperObj, cardProfileObj, imageViewObj);
                executed++;
                if (Boolean.TRUE.equals(res)) {
                    retCode = 1;
                    break;
                }
                long after = readLongField(cardProfileObj, "bAvailableCnt", false);
                if (after >= before) {
                    break;
                }
            }
            if (executed > 0) {
                PLog.i(TAG, "访客页一键回赞完成: 本次 " + executed + " 次, 剩余 " + readLongField(cardProfileObj, "bAvailableCnt", false));
            }
        } catch (Throwable t) {
            PLog.e(TAG, "访客页一键回赞异常", t);
        } finally {
            sInVisitorVote.set(Boolean.FALSE);
        }

        return executed > 0 ? retCode : -1;
    }

    private static long readLongField(Object target, String fieldName, boolean isCard) {
        try {
            Field field = isCard ? sCardAvailVoteField : sProfileAvailVoteField;
            if (field == null || !field.getDeclaringClass().isAssignableFrom(target.getClass())) {
                field = findFieldInHierarchy(target.getClass(), fieldName);
                if (field != null) {
                    field.setAccessible(true);
                    if (isCard) {
                        sCardAvailVoteField = field;
                    } else {
                        sProfileAvailVoteField = field;
                    }
                }
            }
            if (field != null) {
                Object val = field.get(target);
                if (val instanceof Number) {
                    return ((Number) val).longValue();
                }
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Method resolveProfileVoteMethod(Class<?> startClass, String methodName, Class<?> cardClass) {
        Method cached = sProfileVoteMethod;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(startClass)) {
            return cached;
        }

        Method found = findMethodByNameInHierarchy(startClass, methodName, cardClass);
        if (found == null && !"handleVoteBtnClickForGuestProfile".equals(methodName)) {
            found = findMethodByNameInHierarchy(startClass, "handleVoteBtnClickForGuestProfile", cardClass);
        }
        if (found != null) {
            found.setAccessible(true);
            sProfileVoteMethod = found;
        }
        return found;
    }

    private static Method findMethodByNameInHierarchy(Class<?> startClass, String methodName, Class<?> paramClass) {
        if (methodName == null || methodName.isEmpty()) return null;
        Class<?> cur = startClass;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredMethod(methodName, paramClass);
            } catch (NoSuchMethodException ignored) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (methodName.equals(m.getName())) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 1 && pts[0].isAssignableFrom(paramClass)) {
                            return m;
                        }
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Method resolveVisitorVoteMethod(Class<?> helperClass, String methodName, Class<?> profileClass) {
        Method cached = sVisitorVoteMethod;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(helperClass)) {
            return cached;
        }

        Method found = null;
        if (methodName != null && !methodName.isEmpty()) {
            try {
                found = helperClass.getDeclaredMethod(methodName, profileClass, ImageView.class);
            } catch (NoSuchMethodException ignored) {}
        }

        if (found == null) {
            for (Method m : helperClass.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2
                            && pts[0].isAssignableFrom(profileClass)
                            && pts[1] == ImageView.class) {
                        found = m;
                        break;
                    }
                }
            }
        }

        if (found != null) {
            found.setAccessible(true);
            sVisitorVoteMethod = found;
        }
        return found;
    }
}
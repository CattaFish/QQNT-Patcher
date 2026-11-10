package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.util.PLog;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

public class MixedEmoAndTextModule implements IPatchModule {

    private static final String TAG = "MixedEmo";

    private static volatile String sFactoryClassName = null;
    private static volatile String sFactoryMethodName = null;

    private static volatile Class<?> sCachedClass = null;
    private static volatile Method sCachedMethod = null;
    private static volatile Object sCachedInstance = null;
    private static volatile boolean sFactoryResolved = false;

    @Override
    public String getId() {
        return "mixed_emo";
    }

    @Override
    public String getName() {
        return "文字表情混排";
    }

    @Override
    public String getCategory() {
        return CATEGORY_CHAT;
    }

    @Override
    public String getSubName() {
        return "输入框已有文字或回复时，点击表情暂存在输入框混合发送";
    }

    @Override
    public boolean defaultEnabled() {
        return false; // 严格遵循默认关闭原则
    }

    /**
     * 由 Smali 规则动态注入的工厂类与方法名配置
     */
    public static void setupFactory(String className, String methodName) {
        if (className != null && !className.isEmpty()) {
            sFactoryClassName = className;
        }
        if (methodName != null && !methodName.isEmpty()) {
            sFactoryMethodName = methodName;
        }
    }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled()) {
            return;
        }

        // 仅拦截单发图片/表情的场景（避免干扰多图发送等其他批处理）
        if (elements == null || elements.size() != 1) {
            return;
        }

        MsgElement first = elements.get(0);
        if (first == null || first.elementType != 2 || first.picElement == null) {
            return;
        }

        String sourcePath = first.picElement.sourcePath;
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return;
        }

        File file = new File(sourcePath);
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return;
        }

        Activity activity = AppContext.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        EditText editText = findAioEditText(activity);
        if (editText == null) {
            return;
        }

        // 状态判定：输入框已有文字 OR 处于回复状态（通过 Top CompoundDrawable 判定）
        CharSequence currentText = editText.getText();
        boolean hasText = (currentText != null && currentText.length() > 0);
        boolean isReplying = checkIsReplying(editText);

        if (!hasText && !isReplying) {
            return; // 两个条件均不满足，放行正常单发
        }

        // 创建原生 ImageSpan
        Object customSpan = createCustomSpan(activity.getClassLoader(), file);
        if (customSpan == null) {
            PLog.w(TAG, "创建 ImageSpan 失败，放弃拦截并放行原消息");
            return;
        }

        // 核心拦截动作：清空列表（配合 base_rules 的 isEmpty 判定立即 return-void 掐死网络发包）
        elements.clear();

        // 投递到 UI 线程在光标处插入图片 Span，并自动将光标后移
        insertSpanAtCursor(editText, customSpan);
        PLog.i(TAG, "已成功拦截表情并插入输入框混排 [path=" + sourcePath + "]");
    }

    /**
     * 判定是否处于回复状态：
     * 1. 检查 AIOEditText 顶部的 CompoundDrawable 是否非空；
     * 2. 检查 Tag 0x7f0a55c7 是否挂载了回复对象。
     */
    private static boolean checkIsReplying(EditText editText) {
        try {
            Drawable[] drawables = editText.getCompoundDrawables();
            if (drawables != null && drawables.length > 1 && drawables[1] != null) {
                return true;
            }
            Object tag = editText.getTag(0x7f0a55c7);
            if (tag != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 健壮定位当前界面的 AIOEditText（优先资源 ID，兜底递归类名搜索）
     */
    public static EditText findAioEditText(Activity activity) {
        if (activity == null) return null;
        try {
            int resId = activity.getResources().getIdentifier("input", "id", activity.getPackageName());
            if (resId != 0) {
                View v = activity.findViewById(resId);
                if (v instanceof EditText) {
                    return (EditText) v;
                }
            }
        } catch (Throwable ignored) {}

        try {
            View decor = activity.getWindow().getDecorView();
            View v = findViewByClass(decor, "AIOEditText");
            if (v instanceof EditText) {
                return (EditText) v;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static View findViewByClass(View root, String classNameKeyword) {
        if (root == null) return null;
        if (root.getClass().getName().contains(classNameKeyword)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = findViewByClass(vg.getChildAt(i), classNameKeyword);
                if (child != null) return child;
            }
        }
        return null;
    }

    /**
     * 动态反射调用 ImageUtils 生成官方原厂 ImageSpan
     */
    private static Object createCustomSpan(ClassLoader cl, File file) {
        try {
            resolveSpanFactory(cl);
            if (sCachedMethod == null || sCachedInstance == null) {
                return null;
            }
            Uri uri = Uri.fromFile(file);
            return sCachedMethod.invoke(sCachedInstance, file.getAbsolutePath(), uri, false);
        } catch (Throwable t) {
            PLog.e(TAG, "调用 ImageSpan 生成工厂异常", t);
            return null;
        }
    }

    private static synchronized void resolveSpanFactory(ClassLoader cl) {
        if (sFactoryResolved) return;
        try {
            String targetCls = (sFactoryClassName != null && !sFactoryClassName.isEmpty())
                    ? sFactoryClassName : "com.tencent.mobileqq.aio.input.fullscreen.v";
            String targetMethod = (sFactoryMethodName != null && !sFactoryMethodName.isEmpty())
                    ? sFactoryMethodName : "a";

            Class<?> clazz = cl.loadClass(targetCls);
            sCachedClass = clazz;

            // 1. 获取 Kotlin object 单例实例（优先静态 a，其次 INSTANCE，再次遍历自身类型的静态字段）
            Object instance = null;
            try {
                Field fA = clazz.getDeclaredField("a");
                fA.setAccessible(true);
                instance = fA.get(null);
            } catch (Throwable ignored) {}

            if (instance == null) {
                try {
                    Field fInst = clazz.getDeclaredField("INSTANCE");
                    fInst.setAccessible(true);
                    instance = fInst.get(null);
                } catch (Throwable ignored) {}
            }

            if (instance == null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == clazz) {
                        f.setAccessible(true);
                        instance = f.get(null);
                        break;
                    }
                }
            }
            sCachedInstance = instance;

            // 2. 获取 a(String, Uri, boolean) 方法
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(targetMethod) && m.getParameterTypes().length == 3) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts[0] == String.class && pts[1] == Uri.class && (pts[2] == boolean.class || pts[2] == Boolean.class)) {
                        m.setAccessible(true);
                        sCachedMethod = m;
                        break;
                    }
                }
            }

            sFactoryResolved = (sCachedMethod != null && sCachedInstance != null);
            if (sFactoryResolved) {
                PLog.i(TAG, "成功绑定图文混排工厂: " + targetCls + "->" + targetMethod);
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析 ImageSpan 工厂失败", t);
        }
    }

    /**
     * 将 ImageSpan 包装为 SpannableStringBuilder("图片") 并插入光标位置
     */
    private static void insertSpanAtCursor(EditText editText, Object customSpan) {
        SpannableStringBuilder ssb = new SpannableStringBuilder("图片");
        ssb.setSpan(customSpan, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        editText.post(() -> {
            try {
                int start = Math.max(0, editText.getSelectionStart());
                int end = Math.max(0, editText.getSelectionEnd());
                Editable editable = editText.getText();
                if (editable != null) {
                    editable.replace(Math.min(start, end), Math.max(start, end), ssb);
                    editText.setSelection(Math.min(start, end) + 2); // 光标顺畅后移
                }
            } catch (Throwable t) {
                PLog.e(TAG, "光标处插入图片 Span 异常", t);
            }
        });
    }
}
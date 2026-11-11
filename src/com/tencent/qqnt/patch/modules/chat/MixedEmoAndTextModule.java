package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
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
        return false; // 严格遵守默认关闭原则
    }

    /**
     * 接收 Python 动态嗅探结果
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

        // 仅处理单发图片/表情的场景（避免干扰批量图文外发）
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

        // 条件判定：输入框有字 OR 正在回复
        CharSequence currentText = editText.getText();
        boolean hasText = (currentText != null && currentText.length() > 0);
        boolean isReplying = checkIsReplying(editText);

        if (!hasText && !isReplying) {
            return; // 均不满足，放行正常单发表情
        }

        // 反射调用工厂生成原厂 ImageSpan
        Object customSpan = createCustomSpan(activity.getClassLoader(), file);
        if (customSpan == null) {
            PLog.w(TAG, "创建 ImageSpan 失败，安全放行原消息");
            return;
        }

        // 清空列表（base_rules 中 isEmpty 判定生效，立即 return-void 阻断底层 JNI 发包）
        elements.clear();

        // 投递到 UI 线程插入光标位置并推进光标
        insertSpanAtCursor(editText, customSpan);
        PLog.i(TAG, "已成功拦截表情并插入输入框 [path=" + sourcePath + "]");
    }

    /**
     * 被动感知是否处于回复状态
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
     * 健壮定位 AIOEditText（优先资源 ID，兜底递归 View 树）
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

    /**
     * 跨版本动态绑定 ImageUtils 单例与方法
     */
    private static synchronized void resolveSpanFactory(ClassLoader cl) {
        if (sFactoryResolved) return;
        try {
            // 双版本默认类名与动态传入类名候选池
            String[] candidateClasses = (sFactoryClassName != null && !sFactoryClassName.isEmpty())
                    ? new String[]{sFactoryClassName, "com.tencent.mobileqq.aio.input.fullscreen.InputImageUtils", "com.tencent.mobileqq.aio.input.fullscreen.v"}
                    : new String[]{"com.tencent.mobileqq.aio.input.fullscreen.InputImageUtils", "com.tencent.mobileqq.aio.input.fullscreen.v"};

            Class<?> clazz = null;
            for (String cName : candidateClasses) {
                try {
                    clazz = cl.loadClass(cName);
                    if (clazz != null) break;
                } catch (Throwable ignored) {}
            }

            if (clazz == null) {
                PLog.w(TAG, "未找到有效的 InputImageUtils 类");
                return;
            }

            // 1. 获取 Kotlin object 单例（优先静态 a，其次 INSTANCE，兜底扫自身类型静态字段）
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

            // 2. 匹配参数为 (String, Uri, boolean) 的方法（不依赖名称是 a 还是 b）
            Method targetMethod = null;
            if (sFactoryMethodName != null && !sFactoryMethodName.isEmpty()) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(sFactoryMethodName) && isFactoryMethodParamMatch(m)) {
                        m.setAccessible(true);
                        targetMethod = m;
                        break;
                    }
                }
            }

            if (targetMethod == null) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (isFactoryMethodParamMatch(m)) {
                        m.setAccessible(true);
                        targetMethod = m;
                        break;
                    }
                }
            }
            sCachedMethod = targetMethod;

            sFactoryResolved = (sCachedMethod != null && sCachedInstance != null);
            if (sFactoryResolved) {
                PLog.i(TAG, "成功绑定图文混排工厂: " + clazz.getName() + "->" + sCachedMethod.getName());
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析 ImageSpan 工厂失败", t);
        }
    }

    private static boolean isFactoryMethodParamMatch(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        return pts.length == 3 
                && pts[0] == String.class 
                && pts[1] == Uri.class 
                && (pts[2] == boolean.class || pts[2] == Boolean.class);
    }

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
                    editText.setSelection(Math.min(start, end) + 2); // 光标顺滑后移
                }
            } catch (Throwable t) {
                PLog.e(TAG, "光标处插入图片 Span 异常", t);
            }
        });
    }
}
package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
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

    @Override public String getId() { return "mixed_emo"; }
    @Override public String getName() { return "文字表情混排"; }
    @Override public String getCategory() { return CATEGORY_CHAT; }
    @Override public String getSubName() { return "开启后 点击表情暂存至输入框与文字混合发送"; }
    @Override public boolean defaultEnabled() { return false; }

    public static void setupFactory(String className, String methodName) {
        if (className != null && !className.isEmpty()) sFactoryClassName = className;
        if (methodName != null && !methodName.isEmpty()) sFactoryMethodName = methodName;
    }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled() || elements == null || elements.isEmpty()) {
            return;
        }

        PicElement targetPic = null;
        int otherContentCount = 0;

        for (MsgElement elem : elements) {
            if (elem == null) continue;
            if (elem.elementType == 2 && elem.picElement != null) {
                targetPic = elem.picElement;
            } else if (elem.elementType == 7) {
                // 回复元素（类型 7）允许共存
            } else {
                otherContentCount++;
            }
        }

        if (targetPic == null || otherContentCount > 0) {
            return;
        }

        String sourcePath = targetPic.sourcePath;
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

        // 状态判定：输入框已有文字 OR 处于回复状态（因为有了规则B，回复条绝不会被提前清除）
        CharSequence currentText = editText.getText();
        boolean hasText = (currentText != null && currentText.length() > 0);
        boolean isReplying = checkIsReplying(editText);

        if (!hasText && !isReplying) {
            return; // 纯空框且未回复，放行正常单发表情
        }

        Object customSpan = createCustomSpan(activity.getClassLoader(), file);
        if (customSpan == null) {
            PLog.w(TAG, "创建 ImageSpan 失败，安全放行原消息");
            return;
        }

        // 清空列表，触发 sendMsg 的 isEmpty 提前 return-void 阻断发包！
        elements.clear();

        // 1:1 官方原厂插入：前后换行包夹，彻底消除“片”字残留
        insertSpanOfficialWay(editText, customSpan);
        PLog.i(TAG, "成功拦截表情并插入输入框 [path=" + sourcePath + ", isReplying=" + isReplying + "]");
    }

    private static boolean checkIsReplying(EditText editText) {
        try {
            Drawable[] drawables = editText.getCompoundDrawables();
            if (drawables != null && drawables.length > 1 && drawables[1] != null) return true;
            if (editText.getTag(0x7f0a55c7) != null) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static EditText findAioEditText(Activity activity) {
        if (activity == null) return null;
        try {
            int resId = activity.getResources().getIdentifier("input", "id", activity.getPackageName());
            if (resId != 0) {
                View v = activity.findViewById(resId);
                if (v instanceof EditText) return (EditText) v;
            }
        } catch (Throwable ignored) {}

        try {
            View decor = activity.getWindow().getDecorView();
            View v = findViewByClass(decor, "AIOEditText");
            if (v instanceof EditText) return (EditText) v;
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
            if (sCachedMethod == null || sCachedInstance == null) return null;
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
            if (clazz == null) return;

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

            Method targetMethod = null;
            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 3 && pts[0] == String.class && pts[1] == Uri.class && (pts[2] == boolean.class || pts[2] == Boolean.class)) {
                    m.setAccessible(true);
                    targetMethod = m;
                    break;
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

    /**
     * 1:1 官方 FullScreenVBDelegate.o 规范插入：
     * 前后自动判断并追加 '\n' 换行符隔断，彻底杜绝“片”字残留与解析切分错位
     */
    private static void insertSpanOfficialWay(EditText editText, Object customSpan) {
        editText.post(() -> {
            try {
                Editable editable = editText.getText();
                if (editable == null) return;

                int pos = Selection.getSelectionStart(editable);
                if (pos < 0) pos = editable.length();

                // 1. 如果光标前一个字符不是换行符 '\n'，在光标处插入 '\n'
                if (pos > 0 && pos <= editable.length() && editable.charAt(pos - 1) != '\n') {
                    editable.insert(pos, "\n");
                    pos++;
                }

                // 2. 构造包含 "图片" 的 Spannable 并绑定 Span
                SpannableStringBuilder ssb = new SpannableStringBuilder("图片");
                ssb.setSpan(customSpan, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 3. 【核心原厂规范】：末尾追加 '\n'
                ssb.append('\n');

                // 4. 插入到输入框，总跨度为 3 ("图片"2位 + '\n'1位)
                editable.insert(pos, ssb);

                // 5. 光标移至换行后，方便继续打字
                int newCursor = Math.min(pos + 3, editable.length());
                Selection.setSelection(editable, newCursor);

            } catch (Throwable t) {
                PLog.e(TAG, "光标处插入图片 Span 异常", t);
            }
        });
    }
}
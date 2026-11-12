package com.tencent.qqnt.patch.util;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.qqnt.patch.config.ConfigManager;
import me.yxp.qfun.utils.ui.ThemeHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PLog {
    public static final int DEBUG = 3;
    public static final int INFO  = 4;
    public static final int WARN  = 5;
    public static final int ERROR = 6;

    private static final String MAIN_TAG = "QQ_DEBUG";

    // 容量从 300 跃升至 2500，保留更丰富的回溯历史
    private static final int MAX_BUFFER_LINES = 2500;
    // 渲染时展示最近的时效日志条数，保障毫秒级极致渲染
    private static final int MAX_RENDER_LINES = 800;

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    public static class LogEntry {
        public final long timeMs;
        public final int priority;
        public final String tag;
        public final String msg;
        public final String stackTrace;

        public LogEntry(long timeMs, int priority, String tag, String msg, String stackTrace) {
            this.timeMs = timeMs;
            this.priority = priority;
            this.tag = (tag != null && !tag.isEmpty()) ? tag : "App";
            this.msg = (msg != null) ? msg : "";
            this.stackTrace = stackTrace;
        }
    }

    private static final ArrayDeque<LogEntry> sLogBuffer = new ArrayDeque<>(MAX_BUFFER_LINES);

    private static final Set<Object> sLoggedKeys = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<Object, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    static {
        i("Core", "PLog 现代终端运行监视器已就绪 (容量: " + MAX_BUFFER_LINES + " 条)");
    }

    public static void d(String subTag, String msg) {
        log(DEBUG, subTag, msg, null);
    }

    public static void i(String subTag, String msg) {
        log(INFO, subTag, msg, null);
    }

    public static void w(String subTag, String msg) {
        log(WARN, subTag, msg, null);
    }

    public static void w(String subTag, String msg, Throwable tr) {
        log(WARN, subTag, msg, tr);
    }

    public static void e(String subTag, String msg) {
        log(ERROR, subTag, msg, null);
    }

    public static void e(String subTag, String msg, Throwable tr) {
        log(ERROR, subTag, msg, tr);
    }

    public static void once(String subTag, Object key, String msg) {
        if (key != null && sLoggedKeys.add(key)) {
            i(subTag, msg);
        }
    }

    private static void log(int priority, String subTag, String msg, Throwable tr) {
        long now = System.currentTimeMillis();
        String traceStr = (tr != null) ? getStackTrace(tr) : null;
        LogEntry entry = new LogEntry(now, priority, subTag, msg, traceStr);

        synchronized (sLogBuffer) {
            if (sLogBuffer.size() >= MAX_BUFFER_LINES) {
                sLogBuffer.pollFirst();
            }
            sLogBuffer.addLast(entry);
        }

        // GUI 视窗是核心，Logcat 仅在显式开启调试开关或发生严重警示时简单打点
        boolean debugOn = ConfigManager.isDebugLogEnabled();
        if (priority >= WARN || debugOn) {
            String fullMsg = "[" + subTag + "] " + msg;
            if (traceStr != null) {
                fullMsg += "\n" + traceStr;
            }
            switch (priority) {
                case DEBUG: Log.d(MAIN_TAG, fullMsg); break;
                case INFO:  Log.i(MAIN_TAG, fullMsg); break;
                case WARN:  Log.w(MAIN_TAG, fullMsg); break;
                case ERROR: Log.e(MAIN_TAG, fullMsg); break;
            }
        }
    }

    public static int getBufferCount() {
        synchronized (sLogBuffer) {
            return sLogBuffer.size();
        }
    }

    public static void clearBuffer() {
        synchronized (sLogBuffer) {
            sLogBuffer.clear();
        }
    }

    public static String dumpToString() {
        return dumpToString(0);
    }

    public static String dumpToString(int filterPriority) {
        List<LogEntry> snapshot;
        synchronized (sLogBuffer) {
            snapshot = new ArrayList<>(sLogBuffer);
        }

        StringBuilder sb = new StringBuilder();
        for (LogEntry e : snapshot) {
            if (filterPriority > 0 && e.priority < filterPriority) continue;
            String time = TIME_FMT.format(new Date(e.timeMs));
            String lvl = "I";
            if (e.priority == DEBUG) lvl = "D";
            else if (e.priority == WARN) lvl = "W";
            else if (e.priority == ERROR) lvl = "E";

            sb.append("[").append(time).append("][").append(lvl).append("][").append(e.tag).append("] ").append(e.msg).append("\n");
            if (e.stackTrace != null && !e.stackTrace.isEmpty()) {
                sb.append(e.stackTrace).append("\n");
            }
        }
        return sb.toString();
    }

    public static String dumpToFile(Context context) {
        String content = dumpToString();
        File mediaDir = null;
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                mediaDir = mediaDirs[0];
            }
        } catch (Throwable ignored) {}

        if (mediaDir == null) {
            mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + context.getPackageName());
        }

        File logDir = new File(mediaDir, "zzz");
        if (!logDir.exists()) logDir.mkdirs();
        File logFile = new File(logDir, "latest.log");

        try (FileOutputStream fos = new FileOutputStream(logFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(content);
            osw.flush();
            return logFile.getAbsolutePath();
        } catch (Throwable t) {
            return "导出失败: " + t.getMessage();
        }
    }

    // =========================================================================
    // 【现代暗黑终端视窗 GUI 实现】
    // =========================================================================
    public static void showLogDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        boolean isNight = false;
        try {
            isNight = ThemeHelper.INSTANCE.isNightMode();
        } catch (Throwable ignored) {}

        int dialogBgColor     = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int titleTextColor    = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor      = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");

        // 终端专用色彩
        int consoleBgColor    = Color.parseColor("#131417"); // 黑曜石暗底
        int consoleStroke     = Color.parseColor("#26282E");
        int colorTime         = Color.parseColor("#64748B"); // 柔灰时间戳
        int colorTag          = Color.parseColor("#38BDF8"); // 亮青色 Tag
        int colorDebug        = Color.parseColor("#94A3B8"); // 浅灰 Debug
        int colorInfo         = Color.parseColor("#F1F5F9"); // 珍珠白 Info
        int colorWarnTag      = Color.parseColor("#FBBF24"); // 金黄 Warning 标识
        int colorWarnText     = Color.parseColor("#FDE68A"); // 暖黄 Warning 内容
        int colorErrorTag     = Color.parseColor("#F87171"); // 绯红 Error 标识
        int colorErrorText    = Color.parseColor("#FECACA"); // 浅红 Error 内容
        int colorStack        = Color.parseColor("#FCA5A5"); // 堆栈

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int pad = dp2px(activity, 16f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogBgColor);
        bg.setCornerRadius(dp2px(activity, 18f));
        root.setBackground(bg);

        // 1. 顶部标题栏
        TextView title = new TextView(activity);
        title.setText("实时运行日志");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(titleTextColor);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subTitle = new TextView(activity);
        subTitle.setText("已缓冲 " + getBufferCount() + " / " + MAX_BUFFER_LINES + " 条 (支持长按文本自由选区复制)");
        subTitle.setTextSize(11);
        subTitle.setTextColor(subTextColor);
        subTitle.setGravity(Gravity.CENTER);
        subTitle.setPadding(0, dp2px(activity, 2f), 0, dp2px(activity, 10f));
        root.addView(subTitle);

        // 2. 级别筛选胶囊栏 (Filter Tabs - 带有适中呼吸感的中间间距)
        final int[] currentFilter = new int[]{ 0 }; // 0: 全部, 4: INFO, 5: WARN, 6: ERROR
        LinearLayout filterRow = new LinearLayout(activity);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, 0, 0, dp2px(activity, 10f));

        final TextView btnFilterAll  = createCapsule(activity, "全部", true);
        final TextView btnFilterInfo = createCapsule(activity, "信息", false);
        final TextView btnFilterWarn = createCapsule(activity, "警告", false);
        final TextView btnFilterErr  = createCapsule(activity, "错误", false);

        TextView[] tabs = new TextView[]{ btnFilterAll, btnFilterInfo, btnFilterWarn, btnFilterErr };
        int tabGap = dp2px(activity, 7f); // ★ 四个选项卡中间留出 7dp 的清晰间距
        for (int i = 0; i < tabs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp2px(activity, 30f), 1f);
            if (i < tabs.length - 1) {
                lp.rightMargin = tabGap; // 仅前三个选项卡添加右边距，末尾对齐
            }
            filterRow.addView(tabs[i], lp);
        }
        root.addView(filterRow);

        // 3. 黑曜石终端视窗
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(true);

        TextView logView = new TextView(activity);
        logView.setTextSize(11.5f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp2px(activity, 2f), 1.05f);
        logView.setPadding(dp2px(activity, 12f), dp2px(activity, 10f), dp2px(activity, 12f), dp2px(activity, 10f));
        // ★ 核心体验：允许自由选中复制！
        logView.setTextIsSelectable(true);

        GradientDrawable logConsoleBg = new GradientDrawable();
        logConsoleBg.setColor(consoleBgColor);
        logConsoleBg.setCornerRadius(dp2px(activity, 12f));
        logConsoleBg.setStroke(dp2px(activity, 1f), consoleStroke);
        logView.setBackground(logConsoleBg);

        scroll.addView(logView);
        int consoleH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.50f);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, consoleH);
        scrollLp.bottomMargin = dp2px(activity, 12f);
        root.addView(scroll, scrollLp);

        // 4. 动态带颜色富文本渲染器
        Runnable renderLogContent = () -> {
            List<LogEntry> snapshot;
            synchronized (sLogBuffer) {
                snapshot = new ArrayList<>(sLogBuffer);
            }

            int errCount = 0;
            int warnCount = 0;
            for (LogEntry e : snapshot) {
                if (e.priority == ERROR) errCount++;
                else if (e.priority == WARN) warnCount++;
            }
            btnFilterWarn.setText(warnCount > 0 ? "警告(" + warnCount + ")" : "警告");
            btnFilterErr.setText(errCount > 0 ? "错误(" + errCount + ")" : "错误");

            // 过滤与截断，保证流畅性能
            List<LogEntry> filtered = new ArrayList<>();
            for (LogEntry e : snapshot) {
                if (currentFilter[0] > 0 && e.priority < currentFilter[0]) continue;
                filtered.add(e);
            }

            int totalFiltered = filtered.size();
            int startIndex = Math.max(0, totalFiltered - MAX_RENDER_LINES);
            List<LogEntry> displayList = filtered.subList(startIndex, totalFiltered);

            subTitle.setText("已缓冲 " + snapshot.size() + " 条 | 命中 " + totalFiltered + " 条 (最新 " + displayList.size() + " 条)");

            if (displayList.isEmpty()) {
                SpannableStringBuilder emptySsb = new SpannableStringBuilder(" 控制台无对应级别日志");
                emptySsb.setSpan(new ForegroundColorSpan(colorDebug), 0, emptySsb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                logView.setText(emptySsb);
                return;
            }

            SpannableStringBuilder ssb = new SpannableStringBuilder();
            for (LogEntry e : displayList) {
                int lineStart = ssb.length();

                // 1) 时间戳
                String timeStr = TIME_FMT.format(new Date(e.timeMs)) + " ";
                ssb.append(timeStr);
                ssb.setSpan(new ForegroundColorSpan(colorTime), lineStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 2) 级别徽标
                int badgeStart = ssb.length();
                int badgeColor;
                int textColor;
                String lvlBadge;

                if (e.priority == ERROR) {
                    lvlBadge = "[ERROR] ";
                    badgeColor = colorErrorTag;
                    textColor = colorErrorText;
                } else if (e.priority == WARN) {
                    lvlBadge = "[WARN]  ";
                    badgeColor = colorWarnTag;
                    textColor = colorWarnText;
                } else if (e.priority == DEBUG) {
                    lvlBadge = "[DEBUG] ";
                    badgeColor = colorDebug;
                    textColor = colorDebug;
                } else {
                    lvlBadge = "[INFO]  ";
                    badgeColor = Color.parseColor("#34D399"); // 薄荷绿
                    textColor = colorInfo;
                }

                ssb.append(lvlBadge);
                ssb.setSpan(new ForegroundColorSpan(badgeColor), badgeStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), badgeStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 3) 标签 Tag
                int tagStart = ssb.length();
                ssb.append("[").append(e.tag).append("] ");
                ssb.setSpan(new ForegroundColorSpan(colorTag), tagStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 4) 消息内容
                int msgStart = ssb.length();
                ssb.append(e.msg).append("\n");
                ssb.setSpan(new ForegroundColorSpan(textColor), msgStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 5) 堆栈异常（若有）
                if (e.stackTrace != null && !e.stackTrace.isEmpty()) {
                    int stackStart = ssb.length();
                    ssb.append("  ↳ ").append(e.stackTrace.trim().replace("\n", "\n    ")).append("\n");
                    ssb.setSpan(new ForegroundColorSpan(colorStack), stackStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            logView.setText(ssb);

            // ★ 核心交互：自动滑到底部最新内容
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        };

        // 绑定胶囊切换点击
        View.OnClickListener filterClickListener = v -> {
            updateCapsule(btnFilterAll,  v == btnFilterAll);
            updateCapsule(btnFilterInfo, v == btnFilterInfo);
            updateCapsule(btnFilterWarn, v == btnFilterWarn);
            updateCapsule(btnFilterErr,  v == btnFilterErr);

            if (v == btnFilterInfo) currentFilter[0] = INFO;
            else if (v == btnFilterWarn) currentFilter[0] = WARN;
            else if (v == btnFilterErr) currentFilter[0] = ERROR;
            else currentFilter[0] = 0;

            renderLogContent.run();
        };

        btnFilterAll.setOnClickListener(filterClickListener);
        btnFilterInfo.setOnClickListener(filterClickListener);
        btnFilterWarn.setOnClickListener(filterClickListener);
        btnFilterErr.setOnClickListener(filterClickListener);

        // 5. 底部动作按钮行
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        int btnBg = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#E5E5EA");

        Button refreshBtn = createBottomButton(activity, "刷新", Color.parseColor("#007AFF"), Color.WHITE);
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(0, dp2px(activity, 38f), 1f);
        refLp.rightMargin = dp2px(activity, 6f);
        refreshBtn.setOnClickListener(v -> renderLogContent.run());
        btnRow.addView(refreshBtn, refLp);

        Button copyBtn = createBottomButton(activity, "复制全部", btnBg, titleTextColor);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp2px(activity, 38f), 1f);
        copyLp.rightMargin = dp2px(activity, 6f);
        copyBtn.setOnClickListener(v -> {
            String textToCopy = dumpToString(currentFilter[0]);
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("qq_log", textToCopy));
                Toast.makeText(activity, "已复制当前级别日志到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(copyBtn, copyLp);

        Button exportBtn = createBottomButton(activity, "导出", btnBg, titleTextColor);
        LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(0, dp2px(activity, 38f), 1f);
        expLp.rightMargin = dp2px(activity, 6f);
        exportBtn.setOnClickListener(v -> {
            String path = dumpToFile(activity);
            Toast.makeText(activity, "已保存至: " + path, Toast.LENGTH_LONG).show();
        });
        btnRow.addView(exportBtn, expLp);

        Button clearBtn = createBottomButton(activity, "清空", btnBg, isNight ? Color.parseColor("#FF453A") : Color.parseColor("#FF3B30"));
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(0, dp2px(activity, 38f), 1f);
        clearBtn.setOnClickListener(v -> {
            clearBuffer();
            renderLogContent.run();
            Toast.makeText(activity, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(clearBtn, clrLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // 初始首次渲染
        renderLogContent.run();
    }

    private static TextView createCapsule(Context context, String text, boolean selected) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        updateCapsule(tv, selected);
        return tv;
    }

    private static void updateCapsule(TextView tv, boolean selected) {
        boolean isNight = false;
        try {
            isNight = ThemeHelper.INSTANCE.isNightMode();
        } catch (Throwable ignored) {}

        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp2px(tv.getContext(), 8f));
        if (selected) {
            gd.setColor(Color.parseColor("#007AFF"));
            tv.setTextColor(Color.WHITE);
            tv.getPaint().setFakeBoldText(true);
        } else {
            // 未选中状态自适应深浅色：白天模式用柔和淡灰，夜间模式用暗灰
            gd.setColor(isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#E5E5EA"));
            tv.setTextColor(isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666"));
            tv.getPaint().setFakeBoldText(false);
        }
        tv.setBackground(gd);
    }

    private static Button createBottomButton(Context c, String text, int bgColor, int textColor) {
        Button btn = new Button(c);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp2px(c, 9f));
        btn.setBackground(gd);
        return btn;
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
}
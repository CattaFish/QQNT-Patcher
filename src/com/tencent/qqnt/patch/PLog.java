package com.tencent.qqnt.patch;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import me.yxp.qfun.utils.ui.ThemeHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PLog {
    public static final int DEBUG = 3;
    public static final int INFO  = 4;
    public static final int WARN  = 5;
    public static final int ERROR = 6;

    private static final String MAIN_TAG = "QQ_DEBUG";
    private static final int MAX_BUFFER_LINES = 300;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // 内存环形缓冲区：无论是否开启 Logcat 输出，内存永远保留最近 300 条，供随时调阅
    private static final LinkedList<String> sLogBuffer = new LinkedList<>();

    // 全局通用去重缓存
    private static final Set<Object> sLoggedKeys = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<Object, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    static {
        i("Core", "PLog 运行监视器已就绪");
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

    /**
     * 单次输出判定：相同 key 的日志仅打印一次
     */
    public static void once(String subTag, Object key, String msg) {
        if (key != null && sLoggedKeys.add(key)) {
            i(subTag, msg);
        }
    }

    private static void log(int priority, String subTag, String msg, Throwable tr) {
        String time = TIME_FMT.format(new Date());
        String levelChar = "D";
        if (priority == INFO) levelChar = "I";
        else if (priority == WARN) levelChar = "W";
        else if (priority == ERROR) levelChar = "E";

        String formattedLine = "[" + time + "][" + levelChar + "][" + subTag + "] " + msg;

        synchronized (sLogBuffer) {
            if (sLogBuffer.size() >= MAX_BUFFER_LINES) {
                sLogBuffer.removeFirst();
            }
            sLogBuffer.add(formattedLine);
        }

        boolean debugOn = ConfigManager.isDebugLogEnabled();
        if (priority >= WARN || debugOn) {
            String fullMsg = "[" + subTag + "] " + msg;
            if (tr != null) {
                fullMsg += "\n" + getStackTrace(tr);
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
        StringBuilder sb = new StringBuilder();
        synchronized (sLogBuffer) {
            for (String line : sLogBuffer) {
                sb.append(line).append("\n");
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

    public static void showLogDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        // 智能感知日间 / 夜间模式
        boolean isNight = false;
        try {
            isNight = ThemeHelper.INSTANCE.isNightMode();
        } catch (Throwable ignored) {}

        int bgColor           = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int titleTextColor    = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int logBgColor        = isNight ? Color.parseColor("#000000") : Color.parseColor("#F2F2F7");
        int logTextColor      = isNight ? Color.parseColor("#34C759") : Color.parseColor("#1C1C1E");
        int clearBtnBgColor   = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#E5E5EA");
        int clearBtnTextColor = isNight ? Color.parseColor("#FF453A") : Color.parseColor("#FF3B30");

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
        bg.setColor(bgColor);
        bg.setCornerRadius(dp2px(activity, 16f));
        root.setBackground(bg);

        // 标题栏
        TextView title = new TextView(activity);
        title.setText("运行日志 (最近 " + getBufferCount() + " 条)");
        title.setTextSize(16);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(titleTextColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 10f));
        root.addView(title);

        // 日志滚动窗口
        ScrollView scroll = new ScrollView(activity);
        TextView logView = new TextView(activity);
        String text = dumpToString();
        logView.setText(text.isEmpty() ? "暂无日志记录" : text);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE); // 采用等宽编程字体，规范整齐
        logView.setTextColor(logTextColor);
        logView.setPadding(dp2px(activity, 10f), dp2px(activity, 8f), dp2px(activity, 10f), dp2px(activity, 8f));

        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(logBgColor);
        logBg.setCornerRadius(dp2px(activity, 8f));
        logView.setBackground(logBg);

        scroll.addView(logView);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 350f));
        scrollLp.bottomMargin = dp2px(activity, 12f);
        root.addView(scroll, scrollLp);

        // 按钮栏 (刷新 / 导出 / 清空)
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        // 刷新按钮
        Button refreshBtn = new Button(activity);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(13);
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setAllCaps(false);
        GradientDrawable refreshBg = new GradientDrawable();
        refreshBg.setColor(Color.parseColor("#34C759"));
        refreshBg.setCornerRadius(dp2px(activity, 8f));
        refreshBtn.setBackground(refreshBg);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1f);
        refreshLp.rightMargin = dp2px(activity, 6f);
        refreshBtn.setOnClickListener(v -> {
            String updatedText = dumpToString();
            logView.setText(updatedText.isEmpty() ? "暂无日志记录" : updatedText);
            title.setText("运行日志 (最近 " + getBufferCount() + " 条)");
        });
        btnRow.addView(refreshBtn, refreshLp);

        // 导出按钮
        Button exportBtn = new Button(activity);
        exportBtn.setText("导出");
        exportBtn.setTextSize(13);
        exportBtn.setTextColor(Color.WHITE);
        exportBtn.setAllCaps(false);
        GradientDrawable exportBg = new GradientDrawable();
        exportBg.setColor(Color.parseColor("#007AFF"));
        exportBg.setCornerRadius(dp2px(activity, 8f));
        exportBtn.setBackground(exportBg);
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1f);
        exportLp.rightMargin = dp2px(activity, 6f);
        exportBtn.setOnClickListener(v -> {
            String path = dumpToFile(activity);
            Toast.makeText(activity, "已保存至: " + path, Toast.LENGTH_LONG).show();
        });
        btnRow.addView(exportBtn, exportLp);

        // 清空按钮
        Button clearBtn = new Button(activity);
        clearBtn.setText("清空");
        clearBtn.setTextSize(13);
        clearBtn.setTextColor(clearBtnTextColor);
        clearBtn.setAllCaps(false);
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(clearBtnBgColor);
        clearBg.setCornerRadius(dp2px(activity, 8f));
        clearBtn.setBackground(clearBg);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp2px(activity, 40f), 1f);
        clearBtn.setOnClickListener(v -> {
            clearBuffer();
            logView.setText("日志已清空");
            title.setText("运行日志 (0 条)");
        });
        btnRow.addView(clearBtn, clearLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.90);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
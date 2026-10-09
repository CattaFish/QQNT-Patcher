package com.tencent.qqnt.patch.config;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateHelper {

    public static void checkUpdateSilent() {
        new Thread(() -> {
            try {
                URL url = new URL(ConfigManager.UPDATE_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client/" + ConfigManager.VERSION);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String latestTag = extractJsonField(sb.toString(), "tag_name");
                    if (latestTag != null && isNewerVersion(latestTag, ConfigManager.VERSION)) {
                        ConfigManager.setHasNewVersion(true);
                    } else {
                        ConfigManager.setHasNewVersion(false);
                    }
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    public static void checkUpdate(Activity activity) {
        checkUpdate(activity, null);
    }

    public static void checkUpdate(Activity activity, Runnable onComplete) {
        if (activity == null) return;
        ToastHelper.show(activity, "正在检查更新...");

        new Thread(() -> {
            try {
                URL url = new URL(ConfigManager.UPDATE_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client/" + ConfigManager.VERSION);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String json = sb.toString();
                    String latestTag = extractJsonField(json, "tag_name");
                    String releaseUrl = extractJsonField(json, "html_url");

                    if (releaseUrl == null || releaseUrl.isEmpty()) {
                        releaseUrl = ConfigManager.GITHUB_REPO_URL + "/releases";
                    }

                    final String targetUrl = releaseUrl;
                    final String tag = latestTag;

                    activity.runOnUiThread(() -> {
                        if (tag != null && isNewerVersion(tag, ConfigManager.VERSION)) {
                            ConfigManager.setHasNewVersion(true);
                            if (onComplete != null) onComplete.run();

                            ToastHelper.show(activity, "发现新版本: " + tag + "，即将前往下载");
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                activity.startActivity(intent);
                            } catch (Throwable t) {
                                ToastHelper.show(activity, "打开浏览器失败: " + t.getMessage());
                            }
                        } else {
                            ConfigManager.setHasNewVersion(false);
                            if (onComplete != null) onComplete.run();

                            ToastHelper.show(activity, "当前已是最新版本 (" + ConfigManager.VERSION + ")");
                        }
                    });
                } else {
                    activity.runOnUiThread(() -> {
                        ToastHelper.show(activity, "检查更新失败 (HTTP " + code + ")");
                    });
                }
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    ToastHelper.show(activity, "检查更新异常: " + t.getMessage());
                });
            }
        }).start();
    }

    private static String extractJsonField(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static boolean isNewerVersion(String latest, String current) {
        try {
            String v1 = latest.startsWith("v") ? latest.substring(1) : latest;
            String v2 = current.startsWith("v") ? current.substring(1) : current;

            String[] p1 = v1.split("\\.");
            String[] p2 = v2.split("\\.");

            int len = Math.max(p1.length, p2.length);
            for (int i = 0; i < len; i++) {
                int n1 = i < p1.length ? Integer.parseInt(p1[i].replaceAll("\\D+", "")) : 0;
                int n2 = i < p2.length ? Integer.parseInt(p2[i].replaceAll("\\D+", "")) : 0;
                if (n1 > n2) return true;
                if (n1 < n2) return false;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}

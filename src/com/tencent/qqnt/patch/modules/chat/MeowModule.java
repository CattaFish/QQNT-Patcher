package com.tencent.qqnt.patch.modules.chat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;
import me.yxp.qfun.utils.ui.ThemeHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MeowModule implements IPatchModule {
    private static final String TAG = "Meow";

    // 配置持久化 Key
    public static final String KEY_MEOW_WORD_RULES   = "zzz_meow_word_rules";
    public static final String KEY_MEOW_ENABLE_TAIL  = "zzz_meow_enable_tail";
    public static final String KEY_MEOW_TAIL_WORD    = "zzz_meow_tail_word";
    public static final String KEY_MEOW_TAIL_SYMBOL  = "zzz_meow_tail_symbol";
    public static final String KEY_MEOW_REGEX_TAIL1  = "zzz_meow_regex_tail1";
    public static final String KEY_MEOW_REGEX_TAIL2  = "zzz_meow_regex_tail2";

    // 默认内置配置
    public static final String DEFAULT_TAIL_WORD   = "喵";
    public static final String DEFAULT_TAIL_SYMBOL = "~";
    public static final String DEFAULT_REGEX_TAIL1 = "^(.*?)([\\s。\\.！!？\\?~～…]*(?:[\\(（\\[【]\\s*[\\)）\\]】]|[\\(（\\[【])[\\s。\\.！!？\\?~～…]*|[\\s。\\.！!？\\?~～…]+)$";
    public static final String DEFAULT_REGEX_TAIL2 = "^(.*?)([\\s。\\.！!？\\?~～…]*[\\)）\\]】][\\s。\\.！!？\\?~～…]*)$";

    // 内存运行时缓存
    private static final List<WordRule> sCachedRules = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean sCacheLoaded = false;
    private static volatile boolean sEnableTail = true;
    private static volatile String sTailWord = DEFAULT_TAIL_WORD;
    private static volatile String sTailSymbol = DEFAULT_TAIL_SYMBOL;
    private static volatile Pattern sPatternTail1 = null;
    private static volatile Pattern sPatternTail2 = null;

    public static class WordRule {
        public String from;
        public String to;
        public boolean enabled;

        public WordRule(String from, String to, boolean enabled) {
            this.from = from != null ? from : "";
            this.to = to != null ? to : "";
            this.enabled = enabled;
        }
    }

    @Override public String getId() { return "meow_helper"; }
    @Override public String getName() { return "喵喵助手"; }
    @Override public String getCategory() { return CATEGORY_CHAT; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public boolean hasConfig() { return true; }

    @Override
    public String getSubName() {
        if (!isEnabled()) {
            return "自定义发送文本替换与语气词后缀";
        }
        ensureConfigLoaded();
        int activeCount = 0;
        synchronized (sCachedRules) {
            for (WordRule r : sCachedRules) {
                if (r.enabled) activeCount++;
            }
        }
        return "自定义发送文本替换与语气词后缀 (" + activeCount + " 条规则生效)";
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 喵喵助手配置", "配置", true, false,
                v -> onConfigClick(activity, onRefresh)
        ));
        return items;
    }

    @Override
    public void onInit(Context context) {
        ensureConfigLoaded();
    }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled() || elements == null || elements.isEmpty()) return;
        ensureConfigLoaded();

        for (MsgElement elem : elements) {
            if (elem == null) continue;
            if (elem.picElement != null) {
                PicElement pic = elem.picElement;
                PLog.d(TAG, "发包图片探测 -> isFlashPic=" + pic.isFlashPic + ", subType=" + pic.picSubType);
            }
            if (elem.textElement != null && elem.textElement.content != null && !elem.textElement.content.trim().isEmpty()) {
                elem.textElement.content = transformToMeow(elem.textElement.content);
            }
        }
    }

    public static String transformToMeow(String original) {
        if (original == null || original.isEmpty()) return original;
        ensureConfigLoaded();

        String text = original;

        // 1. 词条替换映射
        synchronized (sCachedRules) {
            for (WordRule rule : sCachedRules) {
                if (rule.enabled && !rule.from.isEmpty()) {
                    text = text.replace(rule.from, rule.to);
                }
            }
        }

        // 2. 如果关闭语气后缀，直接放行
        if (!sEnableTail || TextUtils.isEmpty(sTailWord)) {
            return text;
        }

        final String tail = sTailWord;
        final String symbol = sTailSymbol != null ? sTailSymbol : "";

        // 3. 正则表达式 1 匹配（末尾带原有标点/开括号前插入语气词）
        Pattern p1 = sPatternTail1;
        if (p1 != null) {
            try {
                Matcher m = p1.matcher(text);
                if (m.matches()) {
                    String head = m.group(1);
                    String punct = m.group(2);
                    return head + (head.endsWith(tail) ? "" : tail) + punct;
                }
            } catch (Throwable ignored) {}
        }

        // 4. 正则表达式 2 匹配（末尾闭括号识别）
        Pattern p2 = sPatternTail2;
        if (p2 != null) {
            try {
                Matcher cm = p2.matcher(text);
                if (cm.matches()) {
                    String b = cm.group(1);
                    if (!b.contains("（") && !b.contains("(") && !b.contains("【") && !b.contains("[")) {
                        return b + (b.endsWith(tail) ? "" : tail) + cm.group(2);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 5. 兜底追加（句末无标点时，追加「语气词 + 可自定义符号」）
        String fullSuffix = tail + symbol;

        if (text.endsWith(fullSuffix)) {
            return text;
        }
        if (!symbol.isEmpty() && text.endsWith(tail)) {
            return text + symbol;
        }
        if (text.endsWith(tail)) {
            return text;
        }
        return text + fullSuffix;
    }

    private static synchronized void ensureConfigLoaded() {
        if (sCacheLoaded) return;
        reloadConfigFromStorage();
        sCacheLoaded = true;
    }

    public static synchronized void reloadConfigFromStorage() {
        sCachedRules.clear();
        String rulesJson = ConfigManager.getString(KEY_MEOW_WORD_RULES, "");

        if (TextUtils.isEmpty(rulesJson)) {
            sCachedRules.add(new WordRule("你", "主人", true));
            sCachedRules.add(new WordRule("我", "猫猫", true));
        } else {
            try {
                JSONArray arr = new JSONArray(rulesJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    sCachedRules.add(new WordRule(
                            obj.optString("from", ""),
                            obj.optString("to", ""),
                            obj.optBoolean("enabled", true)
                    ));
                }
            } catch (Throwable t) {
                PLog.w(TAG, "解析词条配置失败，恢复默认: " + t.getMessage());
                sCachedRules.add(new WordRule("你", "主人", true));
                sCachedRules.add(new WordRule("我", "猫猫", true));
            }
        }

        sEnableTail = ConfigManager.getString(KEY_MEOW_ENABLE_TAIL, "true").equalsIgnoreCase("true");
        sTailWord = ConfigManager.getString(KEY_MEOW_TAIL_WORD, DEFAULT_TAIL_WORD);
        sTailSymbol = ConfigManager.getString(KEY_MEOW_TAIL_SYMBOL, DEFAULT_TAIL_SYMBOL);

        String reg1 = ConfigManager.getString(KEY_MEOW_REGEX_TAIL1, DEFAULT_REGEX_TAIL1);
        try {
            sPatternTail1 = Pattern.compile(reg1);
        } catch (Throwable t) {
            sPatternTail1 = Pattern.compile(DEFAULT_REGEX_TAIL1);
        }

        String reg2 = ConfigManager.getString(KEY_MEOW_REGEX_TAIL2, DEFAULT_REGEX_TAIL2);
        try {
            sPatternTail2 = Pattern.compile(reg2);
        } catch (Throwable t) {
            sPatternTail2 = Pattern.compile(DEFAULT_REGEX_TAIL2);
        }
    }

    public static synchronized void saveConfigToStorage(List<WordRule> rules, boolean enableTail, String tailWord, String tailSymbol, String reg1, String reg2) {
        try {
            JSONArray arr = new JSONArray();
            for (WordRule r : rules) {
                JSONObject obj = new JSONObject();
                obj.put("from", r.from);
                obj.put("to", r.to);
                obj.put("enabled", r.enabled);
                arr.put(obj);
            }
            ConfigManager.setString(KEY_MEOW_WORD_RULES, arr.toString());
            ConfigManager.setString(KEY_MEOW_ENABLE_TAIL, String.valueOf(enableTail));
            ConfigManager.setString(KEY_MEOW_TAIL_WORD, tailWord != null ? tailWord : DEFAULT_TAIL_WORD);
            ConfigManager.setString(KEY_MEOW_TAIL_SYMBOL, tailSymbol != null ? tailSymbol : "");
            ConfigManager.setString(KEY_MEOW_REGEX_TAIL1, !TextUtils.isEmpty(reg1) ? reg1 : DEFAULT_REGEX_TAIL1);
            ConfigManager.setString(KEY_MEOW_REGEX_TAIL2, !TextUtils.isEmpty(reg2) ? reg2 : DEFAULT_REGEX_TAIL2);

            reloadConfigFromStorage();
        } catch (Throwable t) {
            PLog.e(TAG, "保存喵喵助手配置异常", t);
        }
    }

    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;
        ensureConfigLoaded();

        boolean isNight = ThemeHelper.INSTANCE.isNightMode();

        int bgColor      = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        int textColor    = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        int subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        int inputBgColor = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#F2F2F7");
        int cardBgColor  = isNight ? Color.parseColor("#252527") : Color.parseColor("#F7F8FA");
        int strokeColor  = isNight ? Color.parseColor("#38383A") : Color.parseColor("#E5E5EA");
        int accentColor  = Color.parseColor("#007AFF");
        int greenColor   = Color.parseColor("#34C759");
        int dangerColor  = isNight ? Color.parseColor("#FF453A") : Color.parseColor("#FF3B30");

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int pad = dp2px(activity, 18f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp2px(activity, 18f));
        root.setBackground(bg);

        // 1. 顶部标题栏
        TextView title = new TextView(activity);
        title.setText("喵喵助手配置");
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subTitle = new TextView(activity);
        subTitle.setText("自定义文本替换词条与语气词正则");
        subTitle.setTextSize(12);
        subTitle.setTextColor(subTextColor);
        subTitle.setGravity(Gravity.CENTER);
        subTitle.setPadding(0, dp2px(activity, 4f), 0, dp2px(activity, 12f));
        root.addView(subTitle);

        // 2. 本地编辑状态副本
        final List<WordRule> tempRules = new ArrayList<>();
        synchronized (sCachedRules) {
            for (WordRule r : sCachedRules) {
                tempRules.add(new WordRule(r.from, r.to, r.enabled));
            }
        }
        final boolean[] tempEnableTail = new boolean[]{ sEnableTail };
        final String[] tempTailWord = new String[]{ sTailWord };
        final String[] tempTailSymbol = new String[]{ sTailSymbol };
        final String[] tempRegex1 = new String[]{ ConfigManager.getString(KEY_MEOW_REGEX_TAIL1, DEFAULT_REGEX_TAIL1) };
        final String[] tempRegex2 = new String[]{ ConfigManager.getString(KEY_MEOW_REGEX_TAIL2, DEFAULT_REGEX_TAIL2) };

        // 3. 中间可滚动区域
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout scrollContent = new LinearLayout(activity);
        scrollContent.setOrientation(LinearLayout.VERTICAL);

        // ===== Section 1: 词条替换规则 =====
        LinearLayout rulesHeaderRow = new LinearLayout(activity);
        rulesHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        rulesHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        rulesHeaderRow.setPadding(dp2px(activity, 2f), 0, dp2px(activity, 2f), dp2px(activity, 8f));

        TextView tvRulesSection = new TextView(activity);
        tvRulesSection.setText("词条替换规则");
        tvRulesSection.setTextSize(14);
        tvRulesSection.getPaint().setFakeBoldText(true);
        tvRulesSection.setTextColor(textColor);
        rulesHeaderRow.addView(tvRulesSection, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // + 添加规则 胶囊按钮
        TextView btnAddRule = createActionCapsule(activity, "+ 添加规则", accentColor, Color.WHITE);
rulesHeaderRow.addView(btnAddRule, new LinearLayout.LayoutParams(dp2px(activity, 90f), dp2px(activity, 32f)));
        scrollContent.addView(rulesHeaderRow);

        // 词条卡片列表容器
        LinearLayout rulesListContainer = new LinearLayout(activity);
        rulesListContainer.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(rulesListContainer);

        Runnable renderRulesList = new Runnable() {
            @Override
            public void run() {
                rulesListContainer.removeAllViews();
                if (tempRules.isEmpty()) {
                    TextView emptyView = new TextView(activity);
                    emptyView.setText("暂无替换规则，点击右上角添加");
                    emptyView.setTextSize(12);
                    emptyView.setTextColor(subTextColor);
                    emptyView.setGravity(Gravity.CENTER);
                    emptyView.setPadding(0, dp2px(activity, 14f), 0, dp2px(activity, 14f));
                    rulesListContainer.addView(emptyView);
                    return;
                }

                for (int i = 0; i < tempRules.size(); i++) {
                    final int idx = i;
                    final WordRule rule = tempRules.get(idx);

                    LinearLayout itemCard = new LinearLayout(activity);
                    itemCard.setOrientation(LinearLayout.HORIZONTAL);
                    itemCard.setGravity(Gravity.CENTER_VERTICAL);
                    itemCard.setPadding(dp2px(activity, 12f), dp2px(activity, 8f), dp2px(activity, 10f), dp2px(activity, 8f));

                    GradientDrawable itemBg = new GradientDrawable();
                    itemBg.setColor(cardBgColor);
                    itemBg.setCornerRadius(dp2px(activity, 10f));
                    itemBg.setStroke(dp2px(activity, 1f), strokeColor);
                    itemCard.setBackground(itemBg);

                    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cardLp.bottomMargin = dp2px(activity, 6f);

                    // 映射文本
                    TextView tvMapping = new TextView(activity);
                    tvMapping.setTextSize(13);
                    tvMapping.setIncludeFontPadding(false);
                    tvMapping.getPaint().setFakeBoldText(rule.enabled);
                    tvMapping.setText("“" + rule.from + "”  ➔  “" + rule.to + "”");
                    tvMapping.setTextColor(rule.enabled ? textColor : subTextColor);
                    itemCard.addView(tvMapping, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    // 状态切换按钮
                    TextView btnToggle = createActionCapsule(
                        activity,
                        rule.enabled ? "已开启" : "已停用",
                        rule.enabled ? greenColor : inputBgColor,
                        rule.enabled ? Color.WHITE : subTextColor
                    );
                    btnToggle.setOnClickListener(v -> {
                        rule.enabled = !rule.enabled;
                        run();
                    });
                    LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp2px(activity, 56f), dp2px(activity, 28f));
                    tLp.rightMargin = dp2px(activity, 6f);
                    itemCard.addView(btnToggle, tLp);

                    // 删除按钮
                    TextView btnDel = createActionCapsule(
                            activity,
                            "删除",
                            inputBgColor,
                            dangerColor
                    );
                    btnDel.setOnClickListener(v -> {
                        tempRules.remove(idx);
                        run();
                    });
                    itemCard.addView(btnDel, new LinearLayout.LayoutParams(dp2px(activity, 46f), dp2px(activity, 28f)));

                    rulesListContainer.addView(itemCard, cardLp);
                }
            }
        };
        renderRulesList.run();

        btnAddRule.setOnClickListener(v -> showAddRuleDialog(activity, isNight, textColor, subTextColor, inputBgColor, (from, to) -> {
            tempRules.add(new WordRule(from, to, true));
            renderRulesList.run();
        }));

        // ===== Section 2: 高级功能卡片（无括号冗余，简洁利落） =====
        LinearLayout advContainer = new LinearLayout(activity);
        advContainer.setOrientation(LinearLayout.VERTICAL);
        advContainer.setPadding(dp2px(activity, 12f), dp2px(activity, 12f), dp2px(activity, 12f), dp2px(activity, 12f));
        GradientDrawable advBg = new GradientDrawable();
        advBg.setColor(cardBgColor);
        advBg.setCornerRadius(dp2px(activity, 12f));
        advBg.setStroke(dp2px(activity, 1f), strokeColor);
        advContainer.setBackground(advBg);

        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        advLp.topMargin = dp2px(activity, 10f);
        advLp.bottomMargin = dp2px(activity, 12f);

        // 标题：高级功能
        TextView tvAdvTitle = new TextView(activity);
        tvAdvTitle.setText("高级功能");
        tvAdvTitle.setTextSize(14);
        tvAdvTitle.getPaint().setFakeBoldText(true);
        tvAdvTitle.setTextColor(textColor);
        advContainer.addView(tvAdvTitle);

        // 附加语气后缀开关行
        LinearLayout tailSwitchRow = new LinearLayout(activity);
        tailSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
        tailSwitchRow.setGravity(Gravity.CENTER_VERTICAL);
        tailSwitchRow.setPadding(0, dp2px(activity, 10f), 0, dp2px(activity, 8f));

        TextView tvTailSwitch = new TextView(activity);
        tvTailSwitch.setText("附加语气后缀");
        tvTailSwitch.setTextSize(13);
        tvTailSwitch.setTextColor(textColor);
        tailSwitchRow.addView(tvTailSwitch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView btnTailToggle = createActionCapsule(
                activity,
                tempEnableTail[0] ? "已开启" : "已关闭",
                tempEnableTail[0] ? greenColor : inputBgColor,
                tempEnableTail[0] ? Color.WHITE : subTextColor
        );
        btnTailToggle.setOnClickListener(v -> {
            tempEnableTail[0] = !tempEnableTail[0];
            btnTailToggle.setText(tempEnableTail[0] ? "已开启" : "已关闭");
            btnTailToggle.setTextColor(tempEnableTail[0] ? Color.WHITE : subTextColor);
            updateCapsuleBg(btnTailToggle, tempEnableTail[0] ? greenColor : inputBgColor, dp2px(activity, 8f));
        });
        tailSwitchRow.addView(btnTailToggle, new LinearLayout.LayoutParams(dp2px(activity, 64f), dp2px(activity, 28f)));
        advContainer.addView(tailSwitchRow);

        // ★ 核心升级：语气词与末尾符号（波浪号~）并排两列，支持自由替换与清空
        LinearLayout wordsRow = new LinearLayout(activity);
        wordsRow.setOrientation(LinearLayout.HORIZONTAL);
        wordsRow.setGravity(Gravity.CENTER_VERTICAL);
        wordsRow.setPadding(0, dp2px(activity, 4f), 0, dp2px(activity, 2f));

        // 左列：语气词
        LinearLayout leftCol = new LinearLayout(activity);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvTailWordLabel = new TextView(activity);
        tvTailWordLabel.setText("语气词文本");
        tvTailWordLabel.setTextSize(12);
        tvTailWordLabel.setTextColor(subTextColor);
        leftCol.addView(tvTailWordLabel);

        EditText etTailWord = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "如: 喵", tempTailWord[0]);
        LinearLayout.LayoutParams etLp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 40f));
        etLp1.topMargin = dp2px(activity, 4f);
        leftCol.addView(etTailWord, etLp1);
        wordsRow.addView(leftCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        // 中间间距
        View spacer = new View(activity);
        wordsRow.addView(spacer, new LinearLayout.LayoutParams(dp2px(activity, 10f), dp2px(activity, 1f)));

        // 右列：末尾符号（自由修改/清空原来的 ~）
        LinearLayout rightCol = new LinearLayout(activity);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvSymbolLabel = new TextView(activity);
        tvSymbolLabel.setText("末尾符号");
        tvSymbolLabel.setTextSize(12);
        tvSymbolLabel.setTextColor(subTextColor);
        rightCol.addView(tvSymbolLabel);

        EditText etTailSymbol = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "默认: ~ (可留空)", tempTailSymbol[0]);
        LinearLayout.LayoutParams etLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 40f));
        etLp2.topMargin = dp2px(activity, 4f);
        rightCol.addView(etTailSymbol, etLp2);
        wordsRow.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        advContainer.addView(wordsRow);

        TextView tvPreviewHint = new TextView(activity);
        tvPreviewHint.setText("无标点时末尾组合为「语气词 + 符号」，清空符号则仅追加语气词");
        tvPreviewHint.setTextSize(11);
        tvPreviewHint.setTextColor(subTextColor);
        tvPreviewHint.setPadding(0, dp2px(activity, 4f), 0, dp2px(activity, 6f));
        advContainer.addView(tvPreviewHint);

        // ===== 正则表达式抽屉折叠栏 =====
        final boolean[] isRegexExpanded = new boolean[]{ false };
        final LinearLayout regexContentContainer = new LinearLayout(activity);
        regexContentContainer.setOrientation(LinearLayout.VERTICAL);
        regexContentContainer.setVisibility(View.GONE);

        LinearLayout regexToggleBar = new LinearLayout(activity);
        regexToggleBar.setOrientation(LinearLayout.HORIZONTAL);
        regexToggleBar.setGravity(Gravity.CENTER_VERTICAL);
        regexToggleBar.setPadding(0, dp2px(activity, 10f), 0, dp2px(activity, 4f));

        final TextView tvRegexToggle = new TextView(activity);
        tvRegexToggle.setText("自定义正则表达式 ▼");
        tvRegexToggle.setTextSize(12);
        tvRegexToggle.setTextColor(accentColor);
        regexToggleBar.addView(tvRegexToggle);

        regexToggleBar.setOnClickListener(v -> {
            isRegexExpanded[0] = !isRegexExpanded[0];
            regexContentContainer.setVisibility(isRegexExpanded[0] ? View.VISIBLE : View.GONE);
            tvRegexToggle.setText(isRegexExpanded[0] ? "收起正则表达式 ▲" : "自定义正则表达式 ▼");
        });
        advContainer.addView(regexToggleBar);

        // 正则 1
        LinearLayout reg1Header = new LinearLayout(activity);
        reg1Header.setOrientation(LinearLayout.HORIZONTAL);
        reg1Header.setGravity(Gravity.CENTER_VERTICAL);
        reg1Header.setPadding(0, dp2px(activity, 8f), 0, dp2px(activity, 4f));

        TextView tvReg1Label = new TextView(activity);
        tvReg1Label.setText("正则1: 标点/开括号识别");
        tvReg1Label.setTextSize(12);
        tvReg1Label.setTextColor(subTextColor);
        reg1Header.addView(tvReg1Label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnResetReg1 = new TextView(activity);
        btnResetReg1.setText("恢复默认");
        btnResetReg1.setTextSize(12);
        btnResetReg1.setTextColor(accentColor);
        reg1Header.addView(btnResetReg1);
        regexContentContainer.addView(reg1Header);

        EditText etReg1 = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "正则表达式 1", tempRegex1[0]);
        regexContentContainer.addView(etReg1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f)));
        btnResetReg1.setOnClickListener(v -> etReg1.setText(DEFAULT_REGEX_TAIL1));

        // 正则 2
        LinearLayout reg2Header = new LinearLayout(activity);
        reg2Header.setOrientation(LinearLayout.HORIZONTAL);
        reg2Header.setGravity(Gravity.CENTER_VERTICAL);
        reg2Header.setPadding(0, dp2px(activity, 8f), 0, dp2px(activity, 4f));

        TextView tvReg2Label = new TextView(activity);
        tvReg2Label.setText("正则2: 末尾闭括号识别");
        tvReg2Label.setTextSize(12);
        tvReg2Label.setTextColor(subTextColor);
        reg2Header.addView(tvReg2Label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnResetReg2 = new TextView(activity);
        btnResetReg2.setText("恢复默认");
        btnResetReg2.setTextSize(12);
        btnResetReg2.setTextColor(accentColor);
        reg2Header.addView(btnResetReg2);
        regexContentContainer.addView(reg2Header);

        EditText etReg2 = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "正则表达式 2", tempRegex2[0]);
        regexContentContainer.addView(etReg2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f)));
        btnResetReg2.setOnClickListener(v -> etReg2.setText(DEFAULT_REGEX_TAIL2));

        advContainer.addView(regexContentContainer);
        scrollContent.addView(advContainer, advLp);

        scrollView.addView(scrollContent);
        int maxScrollH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.62f);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollH));

        // 4. 底部功能按钮条
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 12f), 0, 0);

        TextView btnResetAll = createActionCapsule(activity, "恢复默认", inputBgColor, dangerColor);
        LinearLayout.LayoutParams rAllLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        rAllLp.rightMargin = dp2px(activity, 6f);
        btnResetAll.setOnClickListener(v -> {
            tempRules.clear();
            tempRules.add(new WordRule("你", "主人", true));
            tempRules.add(new WordRule("我", "猫猫", true));
            tempEnableTail[0] = true;
            btnTailToggle.setText("已开启");
            btnTailToggle.setTextColor(Color.WHITE);
            updateCapsuleBg(btnTailToggle, greenColor, dp2px(activity, 8f));
            etTailWord.setText(DEFAULT_TAIL_WORD);
            etTailSymbol.setText(DEFAULT_TAIL_SYMBOL);
            etReg1.setText(DEFAULT_REGEX_TAIL1);
            etReg2.setText(DEFAULT_REGEX_TAIL2);
            renderRulesList.run();
            ToastHelper.show(activity, "已恢复初始预设");
        });
        btnRow.addView(btnResetAll, rAllLp);

        TextView cancelBtn = createActionCapsule(activity, "取消", inputBgColor, subTextColor);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
        cancelLp.rightMargin = dp2px(activity, 6f);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn, cancelLp);

        TextView saveBtn = createActionCapsule(activity, "保存并应用", accentColor, Color.WHITE);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1.2f);
        saveBtn.setOnClickListener(v -> {
            String word = etTailWord.getText() != null ? etTailWord.getText().toString().trim() : DEFAULT_TAIL_WORD;
            String symbol = etTailSymbol.getText() != null ? etTailSymbol.getText().toString() : "";
            String r1 = etReg1.getText() != null ? etReg1.getText().toString().trim() : DEFAULT_REGEX_TAIL1;
            String r2 = etReg2.getText() != null ? etReg2.getText().toString().trim() : DEFAULT_REGEX_TAIL2;

            // 正则预检
            try {
                Pattern.compile(r1);
            } catch (Throwable t) {
                ToastHelper.show(activity, "正则 1 语法错误，请检查！");
                return;
            }
            try {
                Pattern.compile(r2);
            } catch (Throwable t) {
                ToastHelper.show(activity, "正则 2 语法错误，请检查！");
                return;
            }

            saveConfigToStorage(tempRules, tempEnableTail[0], word, symbol, r1, r2);
            dialog.dismiss();
            ToastHelper.show(activity, "已保存喵喵助手配置");
            if (onSaved != null) onSaved.run();
        });
        btnRow.addView(saveBtn, saveLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private interface OnRuleAddListener {
        void onAdd(String from, String to);
    }

    private static void showAddRuleDialog(Activity activity, boolean isNight, int textColor, int subTextColor, int inputBgColor, OnRuleAddListener listener) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int pad = dp2px(activity, 18f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isNight ? Color.parseColor("#242426") : Color.WHITE);
        bg.setCornerRadius(dp2px(activity, 14f));
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("添加词条替换");
        title.setTextSize(16);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 12f));
        root.addView(title);

        EditText etFrom = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "原词 (例如: 你)", "");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 42f));
        lp1.bottomMargin = dp2px(activity, 8f);
        root.addView(etFrom, lp1);

        EditText etTo = createStyledEditText(activity, inputBgColor, textColor, subTextColor, "替换后的词 (例如: 主人)", "");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 42f));
        lp2.bottomMargin = dp2px(activity, 14f);
        root.addView(etTo, lp2);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancelBtn = createActionCapsule(activity, "取消", inputBgColor, subTextColor);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp2px(activity, 38f), 1f);
        cLp.rightMargin = dp2px(activity, 6f);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        btnRow.addView(cancelBtn, cLp);

        TextView okBtn = createActionCapsule(activity, "添加", Color.parseColor("#007AFF"), Color.WHITE);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(0, dp2px(activity, 38f), 1f);
        okBtn.setOnClickListener(v -> {
            String from = etFrom.getText() != null ? etFrom.getText().toString().trim() : "";
            String to = etTo.getText() != null ? etTo.getText().toString().trim() : "";
            if (from.isEmpty()) {
                ToastHelper.show(activity, "原词不能为空");
                return;
            }
            d.dismiss();
            if (listener != null) listener.onAdd(from, to);
        });
        btnRow.addView(okBtn, oLp);

        root.addView(btnRow);

        d.setContentView(root);
        d.show();

        if (d.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.82f);
            d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static TextView createActionCapsule(Context context, String text, int bgColor, int textColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(12);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER);
        updateCapsuleBg(tv, bgColor, dp2px(context, 8f));
        return tv;
    }

    private static void updateCapsuleBg(View v, int color, int cornerRadius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(cornerRadius);
        v.setBackground(gd);
    }

    private static EditText createStyledEditText(Context context, int bgCol, int textCol, int hintCol, String hint, String initialText) {
        EditText et = new EditText(context);
        et.setText(initialText != null ? initialText : "");
        et.setHint(hint);
        et.setHintTextColor(hintCol);
        et.setTextColor(textCol);
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setPadding(dp2px(context, 12f), 0, dp2px(context, 12f), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgCol);
        bg.setCornerRadius(dp2px(context, 8f));
        et.setBackground(bg);
        return et;
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
package com.tencent.qqnt.patch.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import me.yxp.qfun.utils.ui.ThemeHelper;

import java.util.ArrayList;
import java.util.List;

public class SettingDialogBuilder {

    public interface OnSwitchListener {
        void onChange(boolean checked);
    }

    public interface OnValChangedListener {
        void onChange(int val);
    }

    public interface OnSelectListener {
        void onSelect(int index);
    }

    public interface OnDialogActionListener {
        void onClick(Dialog dialog);
    }

    public interface OnInputChangedListener {
        void onChange(String text);
    }

    public static class OptionCard {
        public final String title;
        public final String desc;
        public OptionCard(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }
    }

    private final Activity activity;
    private final CharSequence titleText;
    private CharSequence subTitleText;
    private final boolean isNight;

    // 主题色板
    public final int bgColor;
    public final int textColor;
    public final int subTextColor;
    public final int cardBgColor;
    public final int inputBgColor;
    public final int strokeColor;
    public final int accentColor = Color.parseColor("#007AFF");
    public final int greenColor  = Color.parseColor("#34C759");
    public final int dangerColor;

    private final List<View> contentViews = new ArrayList<>();
    private final List<EditText> inputFields = new ArrayList<>();

    private CharSequence posText = "保存";
    private OnDialogActionListener posListener;

    private CharSequence negText = "取消";
    private OnDialogActionListener negListener;

    private CharSequence neutralText;
    private OnDialogActionListener neutralListener;

    public static SettingDialogBuilder build(Activity activity, CharSequence title) {
        return new SettingDialogBuilder(activity, title);
    }

    public SettingDialogBuilder(Activity activity, CharSequence title) {
        this.activity = activity;
        this.titleText = title;
        this.isNight = ThemeHelper.INSTANCE.isNightMode();

        this.bgColor      = isNight ? Color.parseColor("#1C1C1E") : Color.WHITE;
        this.textColor    = isNight ? Color.WHITE : Color.parseColor("#1D1D1F");
        this.subTextColor = isNight ? Color.parseColor("#8E8E93") : Color.parseColor("#666666");
        this.cardBgColor  = isNight ? Color.parseColor("#252527") : Color.parseColor("#F7F8FA");
        this.inputBgColor = isNight ? Color.parseColor("#2C2C2E") : Color.parseColor("#F2F2F7");
        this.strokeColor  = isNight ? Color.parseColor("#38383A") : Color.parseColor("#E5E5EA");
        this.dangerColor  = isNight ? Color.parseColor("#FF453A") : Color.parseColor("#FF3B30");
    }

    public SettingDialogBuilder setSubTitle(CharSequence subTitle) {
        this.subTitleText = subTitle;
        return this;
    }

    public SettingDialogBuilder addTip(CharSequence text, View.OnClickListener onClick) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(onClick != null ? greenColor : subTextColor);
        tv.setLineSpacing(dp2px(activity, 2f), 1f);
        tv.setPadding(dp2px(activity, 4f), 0, dp2px(activity, 4f), dp2px(activity, 10f));
        if (onClick != null) {
            tv.setOnClickListener(onClick);
        }
        contentViews.add(tv);
        return this;
    }

    public SettingDialogBuilder addNoticeBanner(CharSequence title, CharSequence desc, String actionText, View.OnClickListener onAction) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp2px(activity, 12f), dp2px(activity, 10f), dp2px(activity, 12f), dp2px(activity, 10f));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isNight ? Color.parseColor("#2E2415") : Color.parseColor("#FFFBE6"));
        bg.setCornerRadius(dp2px(activity, 10f));
        bg.setStroke(dp2px(activity, 1f), isNight ? Color.parseColor("#59421A") : Color.parseColor("#FFE58F"));
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp2px(activity, 12f);
        card.setLayoutParams(cardLp);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(13);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setTextColor(isNight ? Color.parseColor("#E6A23C") : Color.parseColor("#D46B08"));
        card.addView(tvTitle);

        if (desc != null && desc.length() > 0) {
            TextView tvDesc = new TextView(activity);
            tvDesc.setText(desc);
            tvDesc.setTextSize(11);
            tvDesc.setTextColor(subTextColor);
            tvDesc.setPadding(0, dp2px(activity, 3f), 0, dp2px(activity, 8f));
            card.addView(tvDesc);
        }

        if (actionText != null && onAction != null) {
            Button btn = new Button(activity);
            btn.setText(actionText);
            btn.setTextSize(13);
            btn.setTextColor(Color.WHITE);
            btn.setAllCaps(false);

            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.parseColor("#FA8C16"));
            btnBg.setCornerRadius(dp2px(activity, 8f));
            btn.setBackground(btnBg);

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 36f));
            btn.setLayoutParams(btnLp);
            btn.setOnClickListener(onAction);
            card.addView(btn);
        }

        contentViews.add(card);
        return this;
    }

    public SettingDialogBuilder addSwitch(String title, String desc, boolean initialChecked, OnSwitchListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(activity, 4f), dp2px(activity, 4f), dp2px(activity, 4f), dp2px(activity, 10f));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setTextColor(textColor);
        textCol.addView(tvTitle);

        if (desc != null && !desc.isEmpty()) {
            TextView tvDesc = new TextView(activity);
            tvDesc.setText(desc);
            tvDesc.setTextSize(12);
            tvDesc.setTextColor(subTextColor);
            tvDesc.setPadding(0, dp2px(activity, 2f), 0, 0);
            textCol.addView(tvDesc);
        }
        row.addView(textCol, tLp);

        int trackW = dp2px(activity, 50f);
        int trackH = dp2px(activity, 28f);
        int thumbSize = dp2px(activity, 22f);
        int thumbMargin = dp2px(activity, 3f);

        LinearLayout switchContainer = new LinearLayout(activity);
        switchContainer.setOrientation(LinearLayout.HORIZONTAL);
        switchContainer.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvStatus = new TextView(activity);
        tvStatus.setTextSize(13);
        tvStatus.getPaint().setFakeBoldText(true);
        tvStatus.setPadding(0, 0, dp2px(activity, 8f), 0);
        switchContainer.addView(tvStatus);

        FrameLayout switchTrack = new FrameLayout(activity);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(trackH / 2f);
        switchTrack.setBackground(trackBg);

        View switchThumb = new View(activity);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(Color.WHITE);
        switchThumb.setBackground(thumbBg);

        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(thumbSize, thumbSize);
        thumbLp.gravity = Gravity.CENTER_VERTICAL;
        switchTrack.addView(switchThumb, thumbLp);

        switchContainer.addView(switchTrack, new LinearLayout.LayoutParams(trackW, trackH));
        row.addView(switchContainer);

        final boolean[] state = new boolean[]{ initialChecked };

        Runnable updateUI = () -> {
            boolean cur = state[0];
            if (cur) {
                tvStatus.setText("已开启");
                tvStatus.setTextColor(greenColor);
                trackBg.setColor(greenColor);
                thumbLp.leftMargin = trackW - thumbSize - thumbMargin;
            } else {
                tvStatus.setText("已关闭");
                tvStatus.setTextColor(subTextColor);
                trackBg.setColor(isNight ? Color.parseColor("#3A3A3C") : Color.parseColor("#D1D1D6"));
                thumbLp.leftMargin = thumbMargin;
            }
            switchThumb.setLayoutParams(thumbLp);
        };

        row.setOnClickListener(v -> {
            state[0] = !state[0];
            updateUI.run();
            if (listener != null) listener.onChange(state[0]);
        });

        updateUI.run();
        contentViews.add(row);
        return this;
    }

    public SettingDialogBuilder addStepper(String title, String desc, int min, int max, int initialVal, OnValChangedListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(activity, 4f), dp2px(activity, 4f), dp2px(activity, 4f), dp2px(activity, 12f));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setTextColor(textColor);
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(activity);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(subTextColor);
        tvDesc.setPadding(0, dp2px(activity, 2f), 0, 0);
        textCol.addView(tvDesc);
        row.addView(textCol, tLp);

        LinearLayout stepper = new LinearLayout(activity);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER);
        GradientDrawable stepBg = new GradientDrawable();
        stepBg.setColor(inputBgColor);
        stepBg.setCornerRadius(dp2px(activity, 8f));
        stepper.setBackground(stepBg);
        stepper.setPadding(dp2px(activity, 4f), dp2px(activity, 2f), dp2px(activity, 4f), dp2px(activity, 2f));

        TextView btnMinus = new TextView(activity);
        btnMinus.setText(" - ");
        btnMinus.setTextSize(16);
        btnMinus.getPaint().setFakeBoldText(true);
        btnMinus.setTextColor(accentColor);
        btnMinus.setPadding(dp2px(activity, 8f), dp2px(activity, 4f), dp2px(activity, 8f), dp2px(activity, 4f));

        TextView tvVal = new TextView(activity);
        tvVal.setTextSize(14);
        tvVal.getPaint().setFakeBoldText(true);
        tvVal.setTextColor(textColor);
        tvVal.setGravity(Gravity.CENTER);
        tvVal.setMinWidth(dp2px(activity, 44f));

        TextView btnPlus = new TextView(activity);
        btnPlus.setText(" + ");
        btnPlus.setTextSize(16);
        btnPlus.getPaint().setFakeBoldText(true);
        btnPlus.setTextColor(accentColor);
        btnPlus.setPadding(dp2px(activity, 8f), dp2px(activity, 4f), dp2px(activity, 8f), dp2px(activity, 4f));

        final int[] curVal = new int[]{ initialVal };

        Runnable updateDisplay = () -> {
            tvVal.setText(curVal[0] + " 列");
            tvDesc.setText(desc + " (" + curVal[0] + ")");
        };

        btnMinus.setOnClickListener(v -> {
            if (curVal[0] > min) {
                curVal[0]--;
                updateDisplay.run();
                if (listener != null) listener.onChange(curVal[0]);
            }
        });

        btnPlus.setOnClickListener(v -> {
            if (curVal[0] < max) {
                curVal[0]++;
                updateDisplay.run();
                if (listener != null) listener.onChange(curVal[0]);
            }
        });

        stepper.addView(btnMinus);
        stepper.addView(tvVal);
        stepper.addView(btnPlus);
        row.addView(stepper);

        updateDisplay.run();
        contentViews.add(row);
        return this;
    }

    public SettingDialogBuilder addInput(String hint, String initialText, boolean singleLine, boolean numeric, OnInputChangedListener listener) {
        EditText et = new EditText(activity);
        et.setHint(hint);
        et.setHintTextColor(subTextColor);
        et.setText(initialText != null ? initialText : "");
        et.setTextColor(textColor);
        et.setTextSize(13);
        if (singleLine) et.setSingleLine(true);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setPadding(dp2px(activity, 12f), 0, dp2px(activity, 12f), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(inputBgColor);
        bg.setCornerRadius(dp2px(activity, 10f));
        et.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f));
        lp.bottomMargin = dp2px(activity, 10f);
        et.setLayoutParams(lp);

        if (listener != null) {
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    listener.onChange(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        inputFields.add(et);
        contentViews.add(et);
        return this;
    }

    public SettingDialogBuilder addRadioCards(List<OptionCard> cards, int initialIndex, OnSelectListener listener) {
        final int[] selected = new int[]{ initialIndex };
        final List<LinearLayout> cardViews = new ArrayList<>();

        Runnable refreshUI = () -> {
            for (int i = 0; i < cardViews.size(); i++) {
                LinearLayout c = cardViews.get(i);
                boolean isSel = (i == selected[0]);
                GradientDrawable gd = new GradientDrawable();
                gd.setCornerRadius(dp2px(activity, 12f));
                if (isSel) {
                    gd.setColor(isNight ? Color.parseColor("#1A3B66") : Color.parseColor("#EBF3FF"));
                    gd.setStroke(dp2px(activity, 1.5f), accentColor);
                } else {
                    gd.setColor(cardBgColor);
                    gd.setStroke(0, Color.TRANSPARENT);
                }
                c.setBackground(gd);
            }
        };

        for (int i = 0; i < cards.size(); i++) {
            final int idx = i;
            OptionCard opt = cards.get(idx);

            LinearLayout c = new LinearLayout(activity);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp2px(activity, 14f), dp2px(activity, 12f), dp2px(activity, 14f), dp2px(activity, 12f));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp2px(activity, 10f);
            c.setLayoutParams(lp);

            TextView tvTitle = new TextView(activity);
            tvTitle.setText(opt.title);
            tvTitle.setTextSize(15);
            tvTitle.getPaint().setFakeBoldText(true);
            tvTitle.setTextColor(textColor);
            c.addView(tvTitle);

            if (opt.desc != null && !opt.desc.isEmpty()) {
                TextView tvDesc = new TextView(activity);
                tvDesc.setText(opt.desc);
                tvDesc.setTextSize(12);
                tvDesc.setTextColor(subTextColor);
                tvDesc.setLineSpacing(dp2px(activity, 2f), 1f);
                tvDesc.setPadding(0, dp2px(activity, 4f), 0, 0);
                c.addView(tvDesc);
            }

            c.setOnClickListener(v -> {
                selected[0] = idx;
                refreshUI.run();
                if (listener != null) listener.onSelect(idx);
            });

            cardViews.add(c);
            contentViews.add(c);
        }

        refreshUI.run();
        return this;
    }

    public SettingDialogBuilder addCustomView(View v) {
        contentViews.add(v);
        return this;
    }

    public String getInputValue(int index) {
        if (index >= 0 && index < inputFields.size()) {
            EditText et = inputFields.get(index);
            return et.getText() != null ? et.getText().toString().trim() : "";
        }
        return "";
    }

    public EditText getInputField(int index) {
        return (index >= 0 && index < inputFields.size()) ? inputFields.get(index) : null;
    }

    public SettingDialogBuilder setPositiveButton(CharSequence text, OnDialogActionListener listener) {
        this.posText = text;
        this.posListener = listener;
        return this;
    }

    public SettingDialogBuilder setNegativeButton(CharSequence text, OnDialogActionListener listener) {
        this.negText = text;
        this.negListener = listener;
        return this;
    }

    public SettingDialogBuilder setNeutralButton(CharSequence text, OnDialogActionListener listener) {
        this.neutralText = text;
        this.neutralListener = listener;
        return this;
    }

    public Dialog show() {
        if (activity == null || activity.isFinishing()) return null;

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

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(17);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        if (subTitleText != null && subTitleText.length() > 0) {
            TextView subTitle = new TextView(activity);
            subTitle.setText(subTitleText);
            subTitle.setTextSize(12);
            subTitle.setTextColor(subTextColor);
            subTitle.setGravity(Gravity.CENTER);
            subTitle.setPadding(0, dp2px(activity, 4f), 0, dp2px(activity, 10f));
            root.addView(subTitle);
        } else {
            title.setPadding(0, 0, 0, dp2px(activity, 12f));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout scrollContent = new LinearLayout(activity);
        scrollContent.setOrientation(LinearLayout.VERTICAL);

        for (View v : contentViews) {
            scrollContent.addView(v);
        }

        scroll.addView(scrollContent);
        int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.65f);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(scroll, scrollLp);

        // 底部动作按钮行
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp2px(activity, 12f), 0, 0);

        if (neutralText != null) {
            Button neutralBtn = createButton(activity, neutralText, inputBgColor, dangerColor);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
            nLp.rightMargin = dp2px(activity, 6f);
            neutralBtn.setOnClickListener(v -> {
                if (neutralListener != null) neutralListener.onClick(dialog);
            });
            btnRow.addView(neutralBtn, nLp);
        }

        if (negText != null) {
            Button cancelBtn = createButton(activity, negText, inputBgColor, subTextColor);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1f);
            cLp.rightMargin = dp2px(activity, 6f);
            cancelBtn.setOnClickListener(v -> {
                if (negListener != null) negListener.onClick(dialog);
                else dialog.dismiss();
            });
            btnRow.addView(cancelBtn, cLp);
        }

        if (posText != null) {
            Button saveBtn = createButton(activity, posText, accentColor, Color.WHITE);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp2px(activity, 42f), 1.2f);
            saveBtn.setOnClickListener(v -> {
                if (posListener != null) posListener.onClick(dialog);
                else dialog.dismiss();
            });
            btnRow.addView(saveBtn, sLp);
        }

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        return dialog;
    }

    private static Button createButton(Context c, CharSequence text, int bgColor, int textColor) {
        Button btn = new Button(c);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp2px(c, 10f));
        btn.setBackground(gd);
        return btn;
    }

    public static int dp2px(Context c, float dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}

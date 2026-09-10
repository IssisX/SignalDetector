package com.cory.signalhunter.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class Ui {
    private Ui() { }

    public static int dp(Context c, float value) {
        return (int) (value * c.getResources().getDisplayMetrics().density
            + 0.5f);
    }

    public static GradientDrawable bg(Context c, int color,
            float radius, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        if (border != 0) d.setStroke(dp(c, 1), border);
        return d;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String value, float size,
            int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFontFeatureSettings("tnum");
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public static TextView mono(Context c, String value, float size,
            int color) {
        TextView t = text(c, value, size, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    public static TextView label(Context c, String value) {
        TextView t = text(c, value.toUpperCase(), 10, Palette.MUTED, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    public static TextView button(Context c, String label,
            boolean selected, View.OnClickListener click) {
        TextView t = text(c, label, 13,
            selected ? Palette.BG : Palette.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        t.setMinHeight(dp(c, 44));
        t.setBackground(bg(c, selected ? Palette.ACCENT :
            Palette.RAISED, 8, selected ? 0 : Palette.LINE));
        t.setOnClickListener(click);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 16), dp(c, 15), dp(c, 16), dp(c, 15));
        l.setBackground(bg(c, Palette.PANEL, 12, Palette.LINE));
        return l;
    }

    public static View space(Context c, int height) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height)));
        return v;
    }

    public static View spaceWidth(Context c, int width) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, width), 1));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Palette.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1)));
        return v;
    }

    public static EditText input(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(Palette.TEXT);
        e.setHintTextColor(Palette.MUTED);
        e.setSingleLine(true);
        e.setBackground(bg(c, Palette.RAISED, 8, Palette.LINE));
        e.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.addView(child);
        return s;
    }

    public static LinearLayout.LayoutParams weighted(int weight) {
        return new LinearLayout.LayoutParams(0, -1, weight);
    }

    public static void pad(Context c, View v, int left, int top,
            int right, int bottom) {
        v.setPadding(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
    }
}

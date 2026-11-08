package com.walter.claudewilson;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual primitives for the existing programmatic UI. */
abstract class UiSection extends ContextWrapper {
    // Palette: near-black ground, a slightly lifted surface, one accent for action/selection and
    // three semantic colors that only ever mean pass / attention / failure.
    static final int BG = 0xFF05070B, CARD = 0xFF0E1622, CARD2 = 0xFF16202E,
            STROKE = 0xFF1E2A3B, FG = 0xFFE9EFF8, DIM = 0xFF93A1B5, MUTE = 0xFF5F6F85,
            // Green/amber/red must read as the three verdicts at a glance. The previous green was
            // close enough to the cyan accent to look like decoration rather than "all clear".
            GREEN = 0xFF2FBF71, AMBER = 0xFFF5B301,
            RED = 0xFFF04452, CYAN = 0xFF35E0C8;
    LinearLayout root;

    UiSection(Context context) { super(context); }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    TextView txt(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    /** Rounded surface every card shares, plus a faint top sheen so the plane reads as light. */
    static Drawable card(Context c) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(CARD);
        base.setCornerRadius(dp(c, 16));
        base.setStroke(dp(c, 1), STROKE);
        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x12FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(dp(c, 16));
        return new LayerDrawable(new Drawable[]{base, sheen});
    }

    /**
     * Action pill with a hard offset shadow: pressing sinks the face onto its own shadow, the one
     * motion borrowed from the neubrutalist buttons in the reference library.
     */
    TextView primaryChip(String s, int faceColor, int textColor) {
        TextView t = txt(s, 11, textColor);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        final int lift = dp(this, 2);
        t.setBackground(new HardPill(faceColor, shade(faceColor), dp(this, 18), lift));
        t.setPadding(dp(this, 10), dp(this, 7), dp(this, 10) + lift, dp(this, 7) + lift);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().translationX(lift).translationY(lift)
                            .setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().translationX(0).translationY(0)
                            .setDuration(140).start();
                    break;
                default:
                    break;
            }
            return false;   // keep the click
        });
        return t;
    }

    /** The same pill as primaryChip, for an existing TextView whose state is changing. */
    Drawable primaryPill(int faceColor) {
        return new HardPill(faceColor, shade(faceColor), dp(this, 18), dp(this, 2));
    }

    /** Same hue, darker — the hard shadow colour. */
    private static int shade(int color) {
        int r = (int) ((color >> 16 & 0xFF) * 0.45f);
        int g = (int) ((color >> 8 & 0xFF) * 0.45f);
        int b = (int) ((color & 0xFF) * 0.45f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Rounded face drawn inside a bounds-sized shadow, offset by {@code lift} px. */
    private static final class HardPill extends Drawable {
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final int lift;

        HardPill(int faceColor, int shadowColor, float radius, int lift) {
            face.setColor(faceColor);
            shadow.setColor(shadowColor);
            this.radius = radius;
            this.lift = lift;
        }

        @Override public void draw(Canvas canvas) {
            rect.set(getBounds());
            rect.right -= lift;
            rect.bottom -= lift;
            canvas.drawRoundRect(rect, radius, radius, shadow);
            rect.offset(-lift, -lift);
            canvas.drawRoundRect(rect, radius, radius, face);
        }

        @Override public void setAlpha(int alpha) { face.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { face.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    TextView chip(String s, int color) {
        TextView t = txt(s, 11, color);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD2); g.setCornerRadius(dp(this, 18));
        g.setStroke(dp(this, 1), STROKE);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        t.setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7));
        return t;
    }

    EditText edit(String v) {
        EditText e = new EditText(this);
        e.setTextSize(11); e.setTextColor(FG);
        e.setSingleLine(true); e.setText(v == null ? "" : v);
        e.setPadding(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD2); g.setCornerRadius(dp(this, 10));
        g.setStroke(dp(this, 1), STROKE);
        e.setBackground(g);
        return e;
    }

    /** Section title: a short accent rule plus a bold label, no extra caption. */
    void section(String name) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(this, 26), 0, dp(this, 10));
        View rule = new View(this);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(CYAN);
        bar.setCornerRadius(dp(this, 2));
        rule.setBackground(bar);
        LinearLayout.LayoutParams rlp =
                new LinearLayout.LayoutParams(dp(this, 3), dp(this, 14));
        rlp.rightMargin = dp(this, 8);
        rule.setLayoutParams(rlp);
        row.addView(rule);
        TextView t = txt(name, 13, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.02f);
        row.addView(t);
        root.addView(row);
    }

    /**
     * Two-column variant used when there is no expected value to compare against: one column of
     * dashes is noise, so the row simply drops the comparison column.
     */
    LinearLayout row2(String label, String act, int st) {
        String mark = st == 1 ? "✓" : st == 0 ? "✗" : st == -2 ? " " : st == -3 ? "○" : "—";
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(this, 4), 0, dp(this, 4));
        boolean head = st == -2;
        TextView l = txt("", 11, DIM);
        l.setText(marked(mark, label, head ? CYAN : DIM));
        TextView a = txt(act, 11, head ? CYAN : FG);
        l.setTypeface(Typeface.MONOSPACE);
        a.setTypeface(Typeface.MONOSPACE);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.25f));
        a.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2.0f));
        row.addView(l);
        row.addView(a);
        return row;
    }

    /**
     * Three-column table row: field | expected | actual.
     *
     * <p>Shared so every card that compares values reads the same way; a per-card layout is how
     * the region card ended up as prose in the first place. {@code st}: 1 match, 0 mismatch,
     * -1 not compared, -2 header, -3 not applied yet.
     */
    LinearLayout row3(String label, String exp, String act, int st) {
        String mark = st == 1 ? "✓" : st == 0 ? "✗" : st == -2 ? " "
                : st == -3 ? "○" : "—";
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(this, 5), 0, dp(this, 5));
        boolean head = st == -2;
        // The mark carries the verdict, so only it is coloured; the rest of the row stays calm.
        int markColor = head ? CYAN : st == 1 ? GREEN : st == 0 ? RED
                : st == -3 ? AMBER : DIM;
        TextView l = txt("", 10f, DIM);
        l.setText(marked(mark, label, markColor));
        TextView e = txt(exp, 10f, head ? CYAN : DIM);
        TextView a = txt(act, 10f, head ? CYAN : st == 0 ? RED : st == -3 ? MUTE : FG);
        l.setTypeface(Typeface.MONOSPACE);
        e.setTypeface(Typeface.MONOSPACE);
        a.setTypeface(Typeface.MONOSPACE);
        // The label column needs the most room: product/CSC labels are long and a wrapped label
        // breaks the row rhythm far worse than a wrapped value.
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.45f));
        e.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.4f));
        a.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.7f));
        row.addView(l);
        row.addView(e);
        row.addView(a);
        return row;
    }

    /**
     * Row label with only the status mark coloured. Colouring the whole label made a neutral row
     * look like a warning next to a failing one; the label itself stays one calm grey.
     */
    private static android.text.SpannableString marked(String mark, String label, int markColor) {
        android.text.SpannableString s =
                new android.text.SpannableString(mark + " " + label);
        s.setSpan(new android.text.style.ForegroundColorSpan(markColor), 0,
                Math.max(1, mark.length()), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
}
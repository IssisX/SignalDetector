package com.cory.signalhunter.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import com.cory.signalhunter.core.Observation;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TraceView extends View {
    public interface Selection {
        void selected(Observation observation);
    }
    private final Paint paint = new Paint(3);
    private final Path path = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat(
        "HH:mm:ss", Locale.getDefault());
    private List<Observation> samples = new ArrayList<>();
    private Selection selection;
    private int selected = -1;
    private float left, right, top, bottom;
    private long first, last;
    private int low, high;

    public TraceView(Context context) {
        super(context);
        setMinimumHeight(Ui.dp(context, 240));
    }

    public void setSelection(Selection selection) {
        this.selection = selection;
    }

    public void setSamples(List<Observation> values) {
        samples = new ArrayList<>(values);
        samples.sort((a, b) -> Long.compare(a.sourceNs, b.sourceNs));
        selected = -1;
        invalidate();
    }

    private void line(Canvas c, float x1, float y1,
            float x2, float y2, int color, float width) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStyle(Paint.Style.STROKE);
        c.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas c, String s, float x, float y,
            int color, float size) {
        paint.setColor(color);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(Ui.dp(getContext(), size));
        paint.setStyle(Paint.Style.FILL);
        c.drawText(s, x, y, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        left = 48 * d;
        right = getWidth() - 12 * d;
        top = 22 * d;
        bottom = getHeight() - 35 * d;
        canvas.drawColor(Palette.PANEL);
        if (right <= left || bottom <= top) return;
        if (samples.isEmpty()) {
            text(canvas, "NO MEASUREMENTS", left, getHeight() / 2f,
                Palette.MUTED, 12);
            return;
        }
        low = Integer.MAX_VALUE;
        high = Integer.MIN_VALUE;
        first = Long.MAX_VALUE;
        last = Long.MIN_VALUE;
        for (Observation o : samples) {
            low = Math.min(low, o.rssi);
            high = Math.max(high, o.rssi);
            first = Math.min(first, o.wallMs);
            last = Math.max(last, o.wallMs);
        }
        int padding = Math.max(5, (high - low) / 8);
        low -= padding;
        high += padding;
        if (first == last) {
            first -= 1000;
            last += 1000;
        }
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            line(canvas, left, y, right, y, Palette.LINE, d);
            int dbm = Math.round(high - (high - low) * i / 4f);
            text(canvas, Integer.toString(dbm), 4 * d,
                y + 4 * d, Palette.MUTED, 10);
        }
        text(canvas, clock.format(new Date(first)), left,
            getHeight() - 9 * d, Palette.MUTED, 10);
        String end = clock.format(new Date(last));
        paint.setTextSize(Ui.dp(getContext(), 10));
        float width = paint.measureText(end);
        text(canvas, end, right - width, getHeight() - 9 * d,
            Palette.MUTED, 10);
        line(canvas, left, top, left, bottom, Palette.LINE, d);
        line(canvas, left, bottom, right, bottom, Palette.LINE, d);
        path.reset();
        Observation previous = null;
        for (Observation o : samples) {
            float x = x(o.wallMs);
            float y = y(o.rssi);
            long gap = "BLE".equals(o.radio) ? 15000 : 90000;
            if (previous == null || o.wallMs - previous.wallMs > gap
                    || o.wallMs <= previous.wallMs) path.moveTo(x, y);
            else path.lineTo(x, y);
            previous = o;
        }
        paint.setColor(Palette.ACCENT);
        paint.setStrokeWidth(1.5f * d);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < samples.size(); i++) {
            Observation o = samples.get(i);
            if (i == selected || i == samples.size() - 1 ||
                    samples.size() < 80) {
                paint.setColor(i == selected ? Palette.TEXT : Palette.ACCENT);
                canvas.drawCircle(x(o.wallMs), y(o.rssi),
                    (i == selected ? 4 : 2) * d, paint);
            }
        }
        if (selected >= 0 && selected < samples.size()) {
            Observation o = samples.get(selected);
            float x = x(o.wallMs);
            line(canvas, x, top, x, bottom, Palette.MUTED, d);
        }
    }

    private float x(long ms) {
        return left + (right - left) *
            ((float) (ms - first) / (last - first));
    }
    private float y(int dbm) {
        return bottom - (bottom - top) *
            ((float) (dbm - low) / (high - low));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN &&
                event.getAction() != MotionEvent.ACTION_MOVE) return true;
        if (samples.isEmpty()) return true;
        float touch = event.getX();
        int closest = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < samples.size(); i++) {
            float delta = Math.abs(touch - x(samples.get(i).wallMs));
            if (delta < distance) { distance = delta; closest = i; }
        }
        selected = closest;
        invalidate();
        if (selection != null) selection.selected(samples.get(closest));
        return true;
    }
}

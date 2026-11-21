package com.walter.claudewilson;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.walter.claudewilson.model.GeoTable;
import com.walter.claudewilson.model.MapProjection;

import java.io.InputStream;
import java.util.Locale;

/**
 * Modern zero-network dark-obsidian cartographic canvas.
 *
 * <p>Renders a warm obsidian-bronze Natural Earth basemap ({@code assets/map/world.png}) with
 * crisp DPI-native vector overlays: adaptive lat/lng radar graticule, regional city nodes &amp;
 * labels, accuracy halo ({@code fine / drift / coarse}), and a terracotta reticle. Zero tiles,
 * zero INTERNET permission, zero third-party map SDK.
 */
final class LocationMapView extends View {

    interface Listener {
        void onPick(double latitude, double longitude);
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cityDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionCityDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cityLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private final GeoTable table;
    private final float density;

    private Bitmap world;
    private float scale = 1f;
    private float minScale = 1f;
    private float maxScale = 24f;
    private float tx;
    private float ty;
    private double latitude = 35.6762d;
    private double longitude = 139.6503d;
    private boolean hasPoint = true;
    private boolean simEnabled = true;
    private String activeIso = "";
    private String accuracyMode = "fine";
    private float bottomInsetPx = 0f;
    private Listener listener;

    LocationMapView(Context context, GeoTable table) {
        super(context);
        this.table = table;
        this.density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF081012);

        bitmapPaint.setFilterBitmap(true);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.75f));
        gridPaint.setColor(0x1E7DD3D8);

        gridTextPaint.setColor(0x5985A4A9);
        gridTextPaint.setTextSize(dp(8.5f));
        gridTextPaint.setTypeface(Typeface.MONOSPACE);

        cityDotPaint.setColor(0x5585A4A9);
        regionCityDotPaint.setColor(0xCC7DD3D8);

        cityLabelPaint.setColor(0xB8ECF6F6);
        cityLabelPaint.setTextSize(dp(9.5f));
        cityLabelPaint.setTypeface(Typeface.MONOSPACE);

        haloFillPaint.setStyle(Paint.Style.FILL);
        haloFillPaint.setColor(0x262EC4B6);

        haloStrokePaint.setStyle(Paint.Style.STROKE);
        haloStrokePaint.setStrokeWidth(dp(1.2f));
        haloStrokePaint.setColor(0x882EC4B6);

        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setStrokeWidth(dp(1.8f));
        reticlePaint.setColor(UiSection.ACCENT);

        corePaint.setStyle(Paint.Style.FILL);
        corePaint.setColor(UiSection.ACCENT);

        load();
        gestures = new GestureDetector(context, new Gestures());
        scaleGestures = new ScaleGestureDetector(context, new Scale());
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setActiveIso(String iso) {
        this.activeIso = iso == null ? "" : iso.toLowerCase(Locale.ROOT);
        invalidate();
    }

    void setAccuracyMode(String mode) {
        this.accuracyMode = mode == null || mode.isEmpty() ? "fine" : mode;
        invalidate();
    }

    void setSimEnabled(boolean enabled) {
        this.simEnabled = enabled;
        invalidate();
    }

    /** Reserves bottom visual center offset so the focused pin clears the floating bottom card. */
    void setBottomCardOffsetPx(float px) {
        this.bottomInsetPx = Math.max(0f, px);
    }

    void setPoint(double latitude, double longitude, boolean center) {
        this.latitude = MapProjection.clampLatitude(latitude);
        this.longitude = MapProjection.clampLongitude(longitude);
        this.hasPoint = true;
        if (center) {
            focus();
        }
        invalidate();
    }

    void resetTo(double latitude, double longitude) {
        this.latitude = MapProjection.clampLatitude(latitude);
        this.longitude = MapProjection.clampLongitude(longitude);
        this.hasPoint = true;
        if (getWidth() > 0) {
            scale = Math.min(maxScale, Math.max(minScale * 3.2f, scale));
        }
        focus();
        invalidate();
    }

    void centerOnPin() {
        focus();
        invalidate();
    }

    void zoomBy(float factor) {
        if (world == null || getWidth() == 0) {
            return;
        }
        float before = scale;
        scale = Math.max(minScale, Math.min(maxScale, scale * factor));
        if (before == scale) {
            return;
        }
        float fx = getWidth() / 2f;
        float fy = Math.max(getHeight() * 0.28f, (getHeight() - bottomInsetPx) / 2f);
        tx = fx - (fx - tx) * (scale / before);
        ty = fy - (fy - ty) * (scale / before);
        clamp();
        invalidate();
    }

    double latitude() {
        return latitude;
    }

    double longitude() {
        return longitude;
    }

    private void load() {
        try (InputStream in = getContext().getAssets().open("map/world.png")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inScaled = false;
            world = BitmapFactory.decodeStream(in, null, options);
        } catch (Throwable ignored) {
            world = null;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (world == null || w == 0 || h == 0) {
            return;
        }
        minScale = Math.max(w / (float) world.getWidth(), h / (float) world.getHeight());
        maxScale = minScale * 24f;
        if (scale <= 0f || scale == 1f) {
            scale = Math.min(maxScale, minScale * 3.2f);
        }
        if (hasPoint) {
            focus();
        }
    }

    private void focus() {
        if (world == null || getWidth() == 0) {
            return;
        }
        float x = (float) (MapProjection.x(longitude) * world.getWidth()) * scale;
        float y = (float) (MapProjection.y(latitude) * world.getHeight()) * scale;
        float visualCenterY = Math.max(getHeight() * 0.30f, (getHeight() - bottomInsetPx) / 2f);
        tx = getWidth() / 2f - x;
        ty = visualCenterY - y;
        clamp();
    }

    private void clamp() {
        if (world == null) {
            return;
        }
        float mapWidth = world.getWidth() * scale;
        float mapHeight = world.getHeight() * scale;
        if (mapWidth <= getWidth()) {
            tx = (getWidth() - mapWidth) / 2f;
        } else {
            tx = Math.min(0f, Math.max(getWidth() - mapWidth, tx));
        }
        if (mapHeight <= getHeight()) {
            ty = (getHeight() - mapHeight) / 2f;
        } else {
            ty = Math.min(0f, Math.max(getHeight() - mapHeight, ty));
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (world == null) {
            return;
        }
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(tx, ty);
        canvas.drawBitmap(world, matrix, bitmapPaint);

        drawGraticule(canvas);
        drawCities(canvas);
        if (hasPoint) {
            drawReticle(canvas);
        }
    }

    private void drawGraticule(Canvas canvas) {
        float zoomRatio = scale / Math.max(0.0001f, minScale);
        int stepDeg = zoomRatio > 11f ? 2 : zoomRatio > 5.5f ? 5 : zoomRatio > 2.4f ? 15 : 30;
        float[] pt = new float[2];
        int w = getWidth();
        int h = getHeight();

        for (int lng = -180; lng <= 180; lng += stepDeg) {
            pt[0] = (float) (MapProjection.x(lng) * world.getWidth());
            pt[1] = 0f;
            matrix.mapPoints(pt);
            if (pt[0] >= -2f && pt[0] <= w + 2f) {
                canvas.drawLine(pt[0], 0f, pt[0], h, gridPaint);
            }
        }
        for (int lat = -75; lat <= 75; lat += stepDeg) {
            pt[0] = 0f;
            pt[1] = (float) (MapProjection.y(lat) * world.getHeight());
            matrix.mapPoints(pt);
            if (pt[1] >= -2f && pt[1] <= h + 2f) {
                canvas.drawLine(0f, pt[1], w, pt[1], gridPaint);
                if (pt[1] > dp(90f) && pt[1] < h - bottomInsetPx - dp(16f)) {
                    String tag = Math.abs(lat) + (lat >= 0 ? "N" : "S");
                    canvas.drawText(tag, dp(10f), pt[1] - dp(3f), gridTextPaint);
                }
            }
        }
    }

    private void drawCities(Canvas canvas) {
        float zoomRatio = scale / Math.max(0.0001f, minScale);
        if (zoomRatio < 1.5f) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float[] pt = new float[2];
        boolean showRegionLabels = zoomRatio >= 2.2f;
        boolean showAllLabels = zoomRatio >= 6.0f;
        java.util.List<RectF> placedLabels = new java.util.ArrayList<RectF>();
        float padX = dp(5f);
        float padY = dp(3f);
        float labelH = dp(11f);

        for (GeoTable.Point city : table.all()) {
            if (city.city == null || city.city.isEmpty()) {
                continue;
            }
            pt[0] = (float) (MapProjection.x(city.longitude) * world.getWidth());
            pt[1] = (float) (MapProjection.y(city.latitude) * world.getHeight());
            matrix.mapPoints(pt);
            if (pt[0] < -dp(30f) || pt[0] > w + dp(30f)
                    || pt[1] < -dp(20f) || pt[1] > h + dp(20f)) {
                continue;
            }
            boolean inRegion = !activeIso.isEmpty() && activeIso.equalsIgnoreCase(city.iso);
            canvas.drawCircle(pt[0], pt[1], dp(inRegion ? 2.6f : 1.5f),
                    inRegion ? regionCityDotPaint : cityDotPaint);
            if ((inRegion && showRegionLabels) || showAllLabels) {
                float lx = pt[0] + dp(5.5f);
                float ly = pt[1] + dp(3f);
                float tw = cityLabelPaint.measureText(city.city);
                RectF box = new RectF(lx - padX, ly - labelH - padY, lx + tw + padX, ly + padY);
                boolean collides = false;
                for (int i = 0; i < placedLabels.size(); i++) {
                    if (RectF.intersects(placedLabels.get(i), box)) {
                        collides = true;
                        break;
                    }
                }
                if (!collides) {
                    placedLabels.add(box);
                    cityLabelPaint.setColor(inRegion ? 0xDDECF6F6 : 0x7785A4A9);
                    canvas.drawText(city.city, lx, ly, cityLabelPaint);
                }
            }
        }
    }

    private void drawReticle(Canvas canvas) {
        float[] pt = new float[2];
        pt[0] = (float) (MapProjection.x(longitude) * world.getWidth());
        pt[1] = (float) (MapProjection.y(latitude) * world.getHeight());
        matrix.mapPoints(pt);

        int accent = simEnabled ? UiSection.ACCENT : UiSection.MUTE;
        haloFillPaint.setColor(simEnabled ? 0x262EC4B6 : 0x1A526E73);
        haloStrokePaint.setColor(simEnabled ? 0x882EC4B6 : 0x55526E73);
        reticlePaint.setColor(accent);
        corePaint.setColor(accent);

        float haloRadius = "coarse".equals(accuracyMode) ? dp(34f)
                : "drift".equals(accuracyMode) ? dp(22f) : dp(15f);
        canvas.drawCircle(pt[0], pt[1], haloRadius, haloFillPaint);
        canvas.drawCircle(pt[0], pt[1], haloRadius, haloStrokePaint);

        // Precision radar crosshair ticks
        float inner = dp(6.5f);
        float outer = dp(12.5f);
        canvas.drawLine(pt[0] - outer, pt[1], pt[0] - inner, pt[1], reticlePaint);
        canvas.drawLine(pt[0] + inner, pt[1], pt[0] + outer, pt[1], reticlePaint);
        canvas.drawLine(pt[0], pt[1] - outer, pt[0], pt[1] - inner, reticlePaint);
        canvas.drawLine(pt[0], pt[1] + inner, pt[0], pt[1] + outer, reticlePaint);

        // Luminous terracotta core + ivory center point
        canvas.drawCircle(pt[0], pt[1], dp(4.2f), corePaint);
        corePaint.setColor(simEnabled ? UiSection.FG : UiSection.DIM);
        canvas.drawCircle(pt[0], pt[1], dp(1.5f), corePaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleGestures.onTouchEvent(event);
        if (!scaleGestures.isInProgress()) {
            gestures.onTouchEvent(event);
        }
        return true;
    }

    private void pick(float x, float y) {
        if (world == null) {
            return;
        }
        float bx = (x - tx) / scale;
        float by = (y - ty) / scale;
        double lng = MapProjection.longitudeAt(bx / world.getWidth());
        double lat = MapProjection.latitudeAt(by / world.getHeight());
        setPoint(lat, lng, false);
        if (listener != null) {
            listener.onPick(latitude, longitude);
        }
    }

    private float dp(float value) {
        return value * density;
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            tx -= dx;
            ty -= dy;
            clamp();
            invalidate();
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent event) {
            pick(event.getX(), event.getY());
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent event) {
            if (world == null) {
                return true;
            }
            float before = scale;
            scale = Math.max(minScale, Math.min(maxScale, scale * 2.1f));
            if (before != scale) {
                float fx = event.getX();
                float fy = event.getY();
                tx = fx - (fx - tx) * (scale / before);
                ty = fy - (fy - ty) * (scale / before);
                clamp();
                invalidate();
            }
            return true;
        }

        @Override public void onLongPress(MotionEvent event) {
            pick(event.getX(), event.getY());
        }
    }

    private final class Scale extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector detector) {
            if (world == null) {
                return false;
            }
            float before = scale;
            scale = Math.max(minScale, Math.min(maxScale, scale * detector.getScaleFactor()));
            if (before == scale) {
                return true;
            }
            float fx = detector.getFocusX();
            float fy = detector.getFocusY();
            tx = fx - (fx - tx) * (scale / before);
            ty = fy - (fy - ty) * (scale / before);
            clamp();
            invalidate();
            return true;
        }
    }
}

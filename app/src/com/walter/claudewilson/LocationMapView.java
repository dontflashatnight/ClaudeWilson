package com.walter.claudewilson;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.walter.claudewilson.model.GeoTable;
import com.walter.claudewilson.model.MapProjection;

import java.io.InputStream;

/**
 * Offline equirectangular map. The bitmap is a 4096x2048 Natural Earth render shipped in assets;
 * there are no tiles, no network, no API key and no Google/AMap SDK. Tap or long-press drops the
 * pin; drag pans; pinch zooms.
 */
final class LocationMapView extends View {

    interface Listener {
        void onPick(double latitude, double longitude);
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private final GeoTable table;
    private final float density;

    private Bitmap world;
    private float scale = 1f;
    private float minScale = 1f;
    private float maxScale = 8f;
    private float tx;
    private float ty;
    private double latitude = 35.6762d;
    private double longitude = 139.6503d;
    private boolean hasPoint = true;
    private Listener listener;

    LocationMapView(Context context, GeoTable table) {
        super(context);
        this.table = table;
        this.density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF070B12);
        bitmapPaint.setFilterBitmap(true);
        dotPaint.setColor(0x66AAB9CC);
        pinPaint.setStyle(Paint.Style.STROKE);
        pinPaint.setStrokeWidth(dp(2f));
        pinPaint.setColor(0xFF5EEAD4);
        load();
        gestures = new GestureDetector(context, new Gestures());
        scaleGestures = new ScaleGestureDetector(context, new Scale());
    }

    void setListener(Listener listener) {
        this.listener = listener;
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
            scale = Math.min(maxScale, minScale * 2.2f);
        }
        focus();
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
        maxScale = minScale * 9f;
        if (scale <= 0f || scale == 1f) {
            scale = Math.min(maxScale, minScale * 1.8f);
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
        tx = getWidth() / 2f - x;
        ty = getHeight() / 2f - y;
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

        if (scale > minScale * 2.1f) {
            float[] point = new float[2];
            for (GeoTable.Point city : table.all()) {
                point[0] = (float) (MapProjection.x(city.longitude) * world.getWidth());
                point[1] = (float) (MapProjection.y(city.latitude) * world.getHeight());
                matrix.mapPoints(point);
                canvas.drawCircle(point[0], point[1], dp(1.6f), dotPaint);
            }
        }

        if (hasPoint) {
            float[] point = new float[2];
            point[0] = (float) (MapProjection.x(longitude) * world.getWidth());
            point[1] = (float) (MapProjection.y(latitude) * world.getHeight());
            matrix.mapPoints(point);
            canvas.drawCircle(point[0], point[1], dp(12f), pinPaint);
            pinPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(point[0], point[1], dp(3.5f), pinPaint);
            pinPaint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(point[0], point[1] - dp(26f), point[0], point[1] - dp(12f), pinPaint);
        }
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

        @Override public boolean onSingleTapUp(MotionEvent event) {
            pick(event.getX(), event.getY());
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

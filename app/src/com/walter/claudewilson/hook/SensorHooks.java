package com.walter.claudewilson.hook;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces sensor values in the target process. VirtualRegion only captured sensors for route
 * recording; this port keeps the same environment concept and rewrites the values handed to the
 * target's listener, so accelerometer/gyro/magnetometer/light/pressure stay consistent with a
 * device resting on a desk.
 */
final class SensorHooks {
    static volatile String status = "not-run";
    static final java.util.concurrent.atomic.AtomicInteger events =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final Map<Object, Object> PROXIES =
            Collections.synchronizedMap(new IdentityHashMap<Object, Object>());

    private SensorHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        if (!config.sensorsEnabled) {
            status = "off";
            return;
        }
        try {
            Class<?> manager = XposedHelpers.findClass("android.hardware.SensorManager", loader);
            Class<?> listenerClass = XposedHelpers.findClass(
                    "android.hardware.SensorEventListener", loader);
            XposedBridge.hookAllMethods(manager, "registerListener", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    SensorEventListener listener = null;
                    Sensor sensor = null;
                    for (Object arg : param.args) {
                        if (arg instanceof SensorEventListener) {
                            listener = (SensorEventListener) arg;
                        } else if (arg instanceof Sensor) {
                            sensor = (Sensor) arg;
                        }
                    }
                    if (listener == null) {
                        return;
                    }
                    Object proxy = proxy(listener, listenerClass, config);
                    PROXIES.put(listener, proxy);
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] == listener) {
                            param.args[i] = proxy;
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(manager, "unregisterListener", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        Object proxy = PROXIES.remove(param.args[i]);
                        if (proxy != null) {
                            param.args[i] = proxy;
                        }
                    }
                }
            });
            status = "ok";
            XposedBridge.log("ClaudeWilsonHook sensors status=ok");
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook sensors: " + t);
        }
    }

    private static Object proxy(SensorEventListener listener, Class<?> listenerClass,
            HookConfig config) {
        return Proxy.newProxyInstance(
                listenerClass.getClassLoader() == null
                        ? SensorHooks.class.getClassLoader() : listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                new Handler(listener, config));
    }

    private static float[] values(int type, HookConfig config) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
                return config.sensorAccel != null ? config.sensorAccel
                        : new float[]{0.02f, -0.01f, 9.81f};
            case Sensor.TYPE_MAGNETIC_FIELD:
                return config.sensorMag != null ? config.sensorMag
                        : new float[]{22.0f, -38.0f, 30.0f};
            case Sensor.TYPE_GYROSCOPE:
                return config.sensorGyro != null ? config.sensorGyro
                        : new float[]{0.001f, -0.002f, 0.001f};
            case Sensor.TYPE_LIGHT:
                return new float[]{config.sensorLight};
            case Sensor.TYPE_PRESSURE:
                return new float[]{config.sensorPressure};
            case Sensor.TYPE_PROXIMITY:
                return new float[]{config.sensorProximity};
            case Sensor.TYPE_GRAVITY:
                return config.sensorAccel != null ? config.sensorAccel
                        : new float[]{0.0f, 0.0f, 9.81f};
            case Sensor.TYPE_LINEAR_ACCELERATION:
                return new float[]{0.0f, 0.0f, 0.0f};
            case Sensor.TYPE_ROTATION_VECTOR:
                return new float[]{0.0f, 0.0f, 0.0f, 1.0f};
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                return new float[]{25.0f};
            case Sensor.TYPE_RELATIVE_HUMIDITY:
                return new float[]{45.0f};
            default:
                return null;
        }
    }

    private static final class Handler implements InvocationHandler {
        private final SensorEventListener delegate;
        private final HookConfig config;

        Handler(SensorEventListener delegate, HookConfig config) {
            this.delegate = delegate;
            this.config = config;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("onSensorChanged".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof SensorEvent) {
                SensorEvent event = (SensorEvent) args[0];
                float[] replacement = values(event.sensor.getType(), config);
                if (replacement != null) {
                    int count = Math.min(replacement.length, event.values.length);
                    System.arraycopy(replacement, 0, event.values, 0, count);
                    events.incrementAndGet();
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}

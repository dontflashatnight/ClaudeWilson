package com.walter.claudewilson.hook;

import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.OsConstants;

import com.walter.claudewilson.model.BodyguardEventCodec;
import com.walter.claudewilson.model.BodyguardPolicy;
import com.walter.claudewilson.model.IpGuardPolicy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runtime Bodyguard ("保镖") inside the target process across 6 high-signal collection gates.
 *
 * <p>Zero target-directory files, zero background threads, zero IPC channels:
 * <ul>
 *   <li>Uses caller-stack attribution ({@link BodyguardEventCodec#isFrameworkFrame}) to ignore
 *       Android/AOSP framework internal reads and prevent false positives.</li>
 *   <li>Reports unseen collection via Android 11+ {@link ActivityManager#setProcessStateSummary}
 *       (<= 128 bytes), which ClaudeWilson harvests externally via read-only
 *       {@code dumpsys activity exit-info}.</li>
 *   <li>In {@link BodyguardPolicy#MODE_BLOCK_KILL}, immediately short-circuits the collection call
 *       and terminates the target process before any unseen field can leave the network stack.</li>
 * </ul>
 */
final class BodyguardHooks {
    static volatile String status = "off";

    private static final ThreadLocal<Boolean> IN_GUARD = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> IN_EXIT_FILTER = new ThreadLocal<Boolean>();
    private static volatile long ipCheckLastMs;
    private static volatile boolean ipCachedLocked;
    private static volatile boolean ipCachedExpectTun;
    private static volatile boolean ipCachedTunAlive = true;

    private static final String[][] TRIPWIRE_APIS = {
            {"android.telephony.TelephonyManager", "getImei"},
            {"android.telephony.TelephonyManager", "getMeid"},
            {"android.telephony.TelephonyManager", "getDeviceId"},
            {"android.telephony.TelephonyManager", "getSubscriberId"},
            {"android.telephony.TelephonyManager", "getSimSerialNumber"},
            {"android.telephony.TelephonyManager", "getLine1Number"},
            {"android.os.Build", "getSerial"},
            {"android.net.wifi.WifiInfo", "getMacAddress"},
            {"android.bluetooth.BluetoothAdapter", "getAddress"},
    };

    private BodyguardHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        // Pre-warm Bodyguard codec & policy classes in ART at process boot so an in-place APK
        // upgrade of ClaudeWilson never breaks lazy class resolution on a running target's first
        // tripwire hit.
        warmUpClasses();
        // Always hide historical CWBG1| exit records in memory from target SDKs (Sentry/Datadog),
        // even if the user later switches Bodyguard OFF after an earlier OBSERVE/BLOCK_KILL run.
        installExitInfoStealth(loader);
        if (config == null || BodyguardPolicy.MODE_OFF.equals(config.bodyguardMode)) {
            status = "off";
            return;
        }
        final boolean killMode = BodyguardPolicy.MODE_BLOCK_KILL.equals(config.bodyguardMode);
        if (killMode && isExternalLockActive(config.targetId)) {
            installBootLockGate(loader);
            status = "locked:" + config.targetId;
            return;
        }
        StringBuilder trace = new StringBuilder();

        installTelemetryGate(loader, config, killMode, trace);
        installSysPropGate(loader, config, killMode, trace);
        installSettingsGate(loader, config, killMode, trace);
        installTripwireApiGate(loader, config, killMode, trace);
        installDynamicCodeGate(loader, config, killMode, trace);
        installSensitivePathGate(loader, config, killMode, trace);
        installEgressHostGate(loader, config, killMode, trace);

        status = trace.length() == 0 ? config.bodyguardMode : trace.toString();
        if (HookEntry.debugLog) {
            XposedBridge.log("ClaudeWilsonHook bodyguard mode=" + config.bodyguardMode
                    + " status=" + status);
        }
    }

    /**
     * Fail-closed startup guard when a registered target launches without an active/valid persona
     * payload ({@code HookConfig.load(spec) == null}):
     * <ul>
     *   <li>Always keeps in-memory {@code ExitInfo} stealth active.</li>
     *   <li>In {@code BLOCK_KILL}, hooks {@code Application.attach(Context)} (before any
     *       ContentProvider like Firebase/Sentry/Sift or {@code Application.onCreate} runs),
     *       reports {@code CWBG1|KILLED|NO_PERSONA|config:missing} via AMS, and kills the process
     *       so the target can never run naked.</li>
     *   <li>In {@code OBSERVE}, reports {@code OBSERVED|NO_PERSONA} without killing.</li>
     * </ul>
     */
    static void enforceMissingPersona(final ClassLoader loader, final String guardMode,
                                      final boolean configFileExists) {
        warmUpClasses();
        installExitInfoStealth(loader);
        final String mode = BodyguardPolicy.parseGuardMode(guardMode);
        if (BodyguardPolicy.MODE_OFF.equals(mode)) {
            status = "off:no-persona";
            return;
        }
        final boolean killMode = BodyguardPolicy.shouldFailClosedWithoutPersona(mode);
        final String sigKey = configFileExists ? "config:corrupt" : "config:missing";
        final String action = killMode ? "KILLED" : "OBSERVED";
        final byte[] summary = BodyguardEventCodec.encode(
                action, BodyguardPolicy.GATE_NO_PERSONA, sigKey, "HookEntry", "0");
        final java.util.concurrent.atomic.AtomicBoolean fired =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        status = killMode ? "fail-closed:" + sigKey : "observe:" + sigKey;

        XC_MethodHook gate = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!fired.compareAndSet(false, true)) {
                    return;
                }
                Context ctx = null;
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                    ctx = (Context) param.args[0];
                }
                if (ctx == null) {
                    ctx = HookContext.app(loader);
                }
                if (ctx != null) {
                    try {
                        ActivityManager am = (ActivityManager) ctx.getSystemService(
                                Context.ACTIVITY_SERVICE);
                        if (am != null) {
                            am.setProcessStateSummary(summary);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (killMode) {
                    Process.killProcess(Process.myPid());
                }
            }
        };
        try {
            Class<?> appCls = XposedHelpers.findClassIfExists("android.app.Application", loader);
            if (appCls != null) {
                XposedBridge.hookAllMethods(appCls, "attach", gate);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> instCls = XposedHelpers.findClassIfExists("android.app.Instrumentation", loader);
            if (instCls != null) {
                XposedBridge.hookAllMethods(instCls, "callApplicationOnCreate", gate);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Gate 1: TELEMETRY_KEY (Firelog event_metadata) --------------------------------

    private static void installTelemetryGate(ClassLoader loader, HookConfig config,
                                             boolean killMode, StringBuilder trace) {
        try {
            Class<?> db = XposedHelpers.findClass("android.database.sqlite.SQLiteDatabase", loader);
            XC_MethodHook hook = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_GUARD.get())) {
                        return;
                    }
                    Object[] args = param.args;
                    if (args == null || args.length < 3) {
                        return;
                    }
                    String table = null;
                    ContentValues values = null;
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            table = (String) arg;
                        } else if (arg instanceof ContentValues) {
                            values = (ContentValues) arg;
                        }
                    }
                    if (!"event_metadata".equals(table) || values == null) {
                        return;
                    }
                    String name = values.getAsString("name");
                    if (name == null) {
                        return;
                    }
                    String norm = name.trim();
                    if (norm.isEmpty() || "os-uild".equals(norm)) {
                        return;
                    }
                    String sigKey = "meta:" + norm;
                    if (config.bgAllowTelemetry.contains(norm)
                            || config.bgAllowCustom.contains(sigKey)) {
                        return;
                    }
                    IN_GUARD.set(Boolean.TRUE);
                    try {
                        String caller = findTargetCaller();
                        emitAndMaybeKill(loader, config, killMode,
                                BodyguardPolicy.GATE_TELEMETRY_KEY, sigKey,
                                caller.isEmpty() ? "datatransport" : caller,
                                param, Long.valueOf(-1L), null);
                    } finally {
                        IN_GUARD.remove();
                    }
                }
            };
            XposedBridge.hookAllMethods(db, "insert", hook);
            XposedBridge.hookAllMethods(db, "insertOrThrow", hook);
            XposedBridge.hookAllMethods(db, "insertWithOnConflict", hook);
        } catch (Throwable t) {
            trace.append("telemetry:").append(t.getClass().getSimpleName()).append(';');
        }
    }

    // ---- Gate 2: SYS_PROP (sensitive SystemProperties prefixes) ------------------------

    private static void installSysPropGate(ClassLoader loader, HookConfig config,
                                           boolean killMode, StringBuilder trace) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", loader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_GUARD.get())
                            || param.args == null || param.args.length == 0
                            || param.args[0] == null) {
                        return;
                    }
                    String key = String.valueOf(param.args[0]).trim();
                    if (!BodyguardEventCodec.isSensitivePropKey(key)) {
                        return;
                    }
                    String sigKey = "prop:" + key;
                    if (config.props.containsKey(key)
                            || config.bgAllowProps.contains(key)
                            || config.bgAllowCustom.contains(sigKey)) {
                        return;
                    }
                    IN_GUARD.set(Boolean.TRUE);
                    try {
                        String caller = findTargetCaller();
                        if (caller.isEmpty()) {
                            return; // Pure AOSP framework internal read -> zero false positives.
                        }
                        Object fallback = param.args.length >= 2 ? param.args[1] : "";
                        emitAndMaybeKill(loader, config, killMode,
                                BodyguardPolicy.GATE_SYS_PROP, sigKey, caller,
                                param, fallback, null);
                    } finally {
                        IN_GUARD.remove();
                    }
                }
            };
            XposedBridge.hookAllMethods(sp, "get", hook);
            XposedBridge.hookAllMethods(sp, "getInt", hook);
            XposedBridge.hookAllMethods(sp, "getLong", hook);
            XposedBridge.hookAllMethods(sp, "getBoolean", hook);
        } catch (Throwable t) {
            trace.append("prop:").append(t.getClass().getSimpleName()).append(';');
        }
    }

    // ---- Gate 3: SETTINGS_KEY (sensitive Settings.Secure/Global/System keys) -----------

    private static void installSettingsGate(ClassLoader loader, HookConfig config,
                                            boolean killMode, StringBuilder trace) {
        String[] classes = {
                "android.provider.Settings$Secure",
                "android.provider.Settings$Global",
                "android.provider.Settings$System",
        };
        for (String clsName : classes) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, loader);
                if (cls == null) {
                    continue;
                }
                XC_MethodHook hook = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.args == null || param.args.length < 2
                                || param.args[1] == null) {
                            return;
                        }
                        String key = String.valueOf(param.args[1]).trim();
                        if (!BodyguardEventCodec.isSensitiveSettingsKey(key)) {
                            return;
                        }
                        String sigKey = "settings:" + key;
                        if (BodyguardEventCodec.isBuiltinAllowedSettingsKey(key)
                                || config.bgAllowSettings.contains(key)
                                || config.bgAllowCustom.contains(sigKey)) {
                            return;
                        }
                        IN_GUARD.set(Boolean.TRUE);
                        try {
                            String caller = findTargetCaller();
                            if (caller.isEmpty()) {
                                return;
                            }
                            emitAndMaybeKill(loader, config, killMode,
                                    BodyguardPolicy.GATE_SETTINGS_KEY, sigKey, caller,
                                    param, null, null);
                        } finally {
                            IN_GUARD.remove();
                        }
                    }
                };
                XposedBridge.hookAllMethods(cls, "getString", hook);
                XposedBridge.hookAllMethods(cls, "getStringForUser", hook);
            } catch (Throwable t) {
                trace.append("settings:").append(t.getClass().getSimpleName()).append(';');
            }
        }
    }

    // ---- Gate 4: TRIPWIRE_API (high-risk hardware/identity APIs) -----------------------

    private static void installTripwireApiGate(ClassLoader loader, HookConfig config,
                                               boolean killMode, StringBuilder trace) {
        for (String[] pair : TRIPWIRE_APIS) {
            final String clsName = pair[0];
            final String methodName = pair[1];
            final String shortCls = clsName.substring(clsName.lastIndexOf('.') + 1);
            final String apiShort = shortCls + "." + methodName;
            final String sigKey = "api:" + apiShort;
            if (config.bgAllowApis.contains(apiShort) || config.bgAllowCustom.contains(sigKey)) {
                continue;
            }
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, loader);
                if (cls == null) {
                    continue;
                }
                XposedBridge.hookAllMethods(cls, methodName, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())) {
                            return;
                        }
                        IN_GUARD.set(Boolean.TRUE);
                        try {
                            String caller = findTargetCaller();
                            if (caller.isEmpty()) {
                                return;
                            }
                            emitAndMaybeKill(loader, config, killMode,
                                    BodyguardPolicy.GATE_TRIPWIRE_API, sigKey, caller,
                                    param, null, null);
                        } finally {
                            IN_GUARD.remove();
                        }
                    }
                });
            } catch (Throwable t) {
                trace.append(apiShort).append(':').append(t.getClass().getSimpleName()).append(';');
            }
        }
    }

    // ---- Gate 5: DYNAMIC_CODE (remote DEX / shell exec) --------------------------------

    private static void installDynamicCodeGate(ClassLoader loader, HookConfig config,
                                               boolean killMode, StringBuilder trace) {
        // 5a. DexClassLoader (allow standard system/APK/GMS Dynamite paths)
        if (!config.bgAllowApis.contains("DexClassLoader.<init>")
                && !config.bgAllowCustom.contains("api:DexClassLoader.<init>")) {
            try {
                Class<?> dcl = XposedHelpers.findClassIfExists(
                        "dalvik.system.DexClassLoader", loader);
                if (dcl != null) {
                    XposedBridge.hookAllConstructors(dcl, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(IN_GUARD.get())
                                    || param.args == null || param.args.length == 0
                                    || param.args[0] == null) {
                                return;
                            }
                            String dexPath = String.valueOf(param.args[0]);
                            if (isTrustedDexPath(dexPath)) {
                                return;
                            }
                            IN_GUARD.set(Boolean.TRUE);
                            try {
                                String caller = findTargetCaller();
                                if (caller.isEmpty()) {
                                    return;
                                }
                                emitAndMaybeKill(loader, config, killMode,
                                        BodyguardPolicy.GATE_DYNAMIC_CODE,
                                        "api:DexClassLoader.<init>", caller,
                                        param, null, new SecurityException("blocked"));
                            } finally {
                                IN_GUARD.remove();
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                trace.append("dcl:").append(t.getClass().getSimpleName()).append(';');
            }
        }

        // 5a-2. InMemoryDexClassLoader (remote ByteBuffer DEX loading)
        if (!config.bgAllowApis.contains("InMemoryDexClassLoader.<init>")
                && !config.bgAllowCustom.contains("api:InMemoryDexClassLoader.<init>")) {
            try {
                Class<?> imdcl = XposedHelpers.findClassIfExists(
                        "dalvik.system.InMemoryDexClassLoader", loader);
                if (imdcl != null) {
                    XposedBridge.hookAllConstructors(imdcl, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(IN_GUARD.get())) {
                                return;
                            }
                            IN_GUARD.set(Boolean.TRUE);
                            try {
                                String caller = findTargetCaller(true);
                                if (caller.isEmpty()) {
                                    return;
                                }
                                emitAndMaybeKill(loader, config, killMode,
                                        BodyguardPolicy.GATE_DYNAMIC_CODE,
                                        "api:InMemoryDexClassLoader.<init>", caller,
                                        param, null, new SecurityException("blocked"));
                            } finally {
                                IN_GUARD.remove();
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                trace.append("imdcl:").append(t.getClass().getSimpleName()).append(';');
            }
        }

        // 5b. Runtime.exec & ProcessBuilder.start
        if (!config.bgAllowApis.contains("Runtime.exec")
                && !config.bgAllowCustom.contains("api:Runtime.exec")) {
            try {
                XposedBridge.hookAllMethods(Runtime.class, "exec", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.hasThrowable() || param.getResult() != null) {
                            return;
                        }
                        String cmdTag = extractExecTag(param.args != null && param.args.length > 0
                                ? param.args[0] : null);
                        String sigKey = cmdTag.isEmpty()
                                ? "api:Runtime.exec" : "api:Runtime.exec:" + cmdTag;
                        if (config.bgAllowCustom.contains(sigKey)) {
                            return;
                        }
                        IN_GUARD.set(Boolean.TRUE);
                        try {
                            String caller = findTargetCaller();
                            if (caller.isEmpty()) {
                                return;
                            }
                            emitAndMaybeKill(loader, config, killMode,
                                    BodyguardPolicy.GATE_DYNAMIC_CODE, sigKey, caller,
                                    param, null, new SecurityException("blocked"));
                        } finally {
                            IN_GUARD.remove();
                        }
                    }
                });
            } catch (Throwable t) {
                trace.append("exec:").append(t.getClass().getSimpleName()).append(';');
            }
        }
        if (!config.bgAllowApis.contains("ProcessBuilder.start")
                && !config.bgAllowCustom.contains("api:ProcessBuilder.start")) {
            try {
                XposedBridge.hookAllMethods(ProcessBuilder.class, "start", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.hasThrowable() || param.getResult() != null) {
                            return;
                        }
                        String cmdTag = extractExecTag(param.thisObject instanceof ProcessBuilder
                                ? ((ProcessBuilder) param.thisObject).command() : null);
                        String sigKey = cmdTag.isEmpty()
                                ? "api:ProcessBuilder.start"
                                : "api:ProcessBuilder.start:" + cmdTag;
                        if (config.bgAllowCustom.contains(sigKey)) {
                            return;
                        }
                        IN_GUARD.set(Boolean.TRUE);
                        try {
                            String caller = findTargetCaller();
                            if (caller.isEmpty()) {
                                return;
                            }
                            emitAndMaybeKill(loader, config, killMode,
                                    BodyguardPolicy.GATE_DYNAMIC_CODE,
                                    sigKey, caller,
                                    param, null, new SecurityException("blocked"));
                        } finally {
                            IN_GUARD.remove();
                        }
                    }
                });
            } catch (Throwable t) {
                trace.append("pb:").append(t.getClass().getSimpleName()).append(';');
            }
        }
    }

    // ---- Gate 6: SENSITIVE_PATH (unlisted hardware /proc and /sys files) ---------------

    private static void installSensitivePathGate(ClassLoader loader, HookConfig config,
                                                 boolean killMode, StringBuilder trace) {
        XC_MethodHook streamHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_GUARD.get())
                        || param.args == null || param.args.length == 0
                        || param.args[0] == null) {
                    return;
                }
                String path = null;
                if (param.args[0] instanceof String) {
                    path = (String) param.args[0];
                } else if (param.args[0] instanceof File) {
                    path = ((File) param.args[0]).getPath();
                }
                checkSensitivePath(loader, config, killMode, path, param,
                        new FileNotFoundException(path));
            }
        };
        try {
            XposedBridge.hookAllConstructors(java.io.FileInputStream.class, streamHook);
        } catch (Throwable t) {
            trace.append("fis:").append(t.getClass().getSimpleName()).append(';');
        }
        try {
            XposedBridge.hookAllConstructors(RandomAccessFile.class, streamHook);
        } catch (Throwable t) {
            trace.append("raf:").append(t.getClass().getSimpleName()).append(';');
        }
        // Catch Java NIO (Files.readAllBytes / FileChannel.open) via Os.open & IoBridge.open.
        try {
            Class<?> osCls = XposedHelpers.findClassIfExists("android.system.Os", loader);
            if (osCls != null) {
                XposedBridge.hookAllMethods(osCls, "open", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof String)) {
                            return;
                        }
                        String path = (String) param.args[0];
                        checkSensitivePath(loader, config, killMode, path, param,
                                new ErrnoException("open", OsConstants.ENOENT));
                    }
                });
            }
        } catch (Throwable t) {
            trace.append("osopen:").append(t.getClass().getSimpleName()).append(';');
        }
        try {
            Class<?> ioBridge = XposedHelpers.findClassIfExists("libcore.io.IoBridge", loader);
            if (ioBridge != null) {
                XposedBridge.hookAllMethods(ioBridge, "open", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof String)) {
                            return;
                        }
                        String path = (String) param.args[0];
                        checkSensitivePath(loader, config, killMode, path, param,
                                new FileNotFoundException(path));
                    }
                });
            }
        } catch (Throwable t) {
            trace.append("iobridge:").append(t.getClass().getSimpleName()).append(';');
        }
    }

    private static void checkSensitivePath(ClassLoader loader, HookConfig config, boolean killMode,
                                           String path, XC_MethodHook.MethodHookParam param,
                                           Throwable blockThrowable) {
        if (!BodyguardEventCodec.isSensitivePath(path)) {
            return;
        }
        String sigKey = "path:" + path;
        if (config.bgAllowPaths.contains(path)
                || config.bgAllowCustom.contains(sigKey)) {
            return;
        }
        IN_GUARD.set(Boolean.TRUE);
        try {
            String caller = findTargetCaller();
            if (caller.isEmpty()) {
                return;
            }
            emitAndMaybeKill(loader, config, killMode,
                    BodyguardPolicy.GATE_SENSITIVE_PATH, sigKey, caller,
                    param, null, blockThrowable);
        } finally {
            IN_GUARD.remove();
        }
    }

    // ---- Gate 7: EGRESS_HOST (audited outbound domain allowlist) -----------------------

    private static void installEgressHostGate(ClassLoader loader, HookConfig config,
                                              boolean killMode, StringBuilder trace) {
        // Arm when either the target has a populated host baseline (Gate 7) or IP drop protection
        // (Gate 8) is active; Gate 7 allowlist enforcement still checks non-empty bgAllowHosts.
        boolean hasHostAllow = config.bgAllowHosts != null && !config.bgAllowHosts.isEmpty();
        if (!hasHostAllow && !config.bgIpKillOnDrop) {
            return;
        }
        XC_MethodHook dnsHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_GUARD.get())
                        || param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof String)) {
                    return;
                }
                checkEgressHost(loader, config, killMode, (String) param.args[0],
                        param, null, new java.net.UnknownHostException("blocked"));
            }
        };
        try {
            Class<?> inetCls = XposedHelpers.findClassIfExists("java.net.InetAddress", loader);
            if (inetCls != null) {
                XposedBridge.hookAllMethods(inetCls, "getAllByName", dnsHook);
                XposedBridge.hookAllMethods(inetCls, "getByName", dnsHook);
            }
        } catch (Throwable t) {
            trace.append("inet:").append(t.getClass().getSimpleName()).append(';');
        }
        try {
            Class<?> netCls = XposedHelpers.findClassIfExists("android.net.Network", loader);
            if (netCls != null) {
                XposedBridge.hookAllMethods(netCls, "getAllByName", dnsHook);
                XposedBridge.hookAllMethods(netCls, "getByName", dnsHook);
            }
        } catch (Throwable t) {
            trace.append("netdns:").append(t.getClass().getSimpleName()).append(';');
        }
        try {
            Class<?> resolverCls = XposedHelpers.findClassIfExists(
                    "android.net.DnsResolver", loader);
            if (resolverCls != null) {
                XposedBridge.hookAllMethods(resolverCls, "query", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.args == null || param.args.length < 2
                                || !(param.args[1] instanceof String)) {
                            return;
                        }
                        checkEgressHost(loader, config, killMode, (String) param.args[1],
                                param, null, null);
                    }
                });
            }
        } catch (Throwable t) {
            trace.append("dnsres:").append(t.getClass().getSimpleName()).append(';');
        }
        try {
            Class<?> urlCls = XposedHelpers.findClassIfExists("java.net.URL", loader);
            if (urlCls != null) {
                XposedBridge.hookAllMethods(urlCls, "openConnection", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get()) || param.thisObject == null) {
                            return;
                        }
                        String host = BodyguardEventCodec.extractHttpUrlHost(
                                param.thisObject.toString());
                        if (host.isEmpty()) {
                            return;
                        }
                        checkEgressHost(loader, config, killMode, host,
                                param, null, new java.io.IOException("blocked"));
                    }
                });
            }
        } catch (Throwable t) {
            trace.append("url:").append(t.getClass().getSimpleName()).append(';');
        }
        try {
            Class<?> sockCls = XposedHelpers.findClassIfExists("java.net.Socket", loader);
            if (sockCls != null) {
                XposedBridge.hookAllMethods(sockCls, "connect", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_GUARD.get())
                                || param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof java.net.InetSocketAddress)) {
                            return;
                        }
                        String host = ((java.net.InetSocketAddress) param.args[0]).getHostString();
                        checkEgressHost(loader, config, killMode, host,
                                param, null, new java.io.IOException("blocked"));
                    }
                });
            }
        } catch (Throwable t) {
            trace.append("sock:").append(t.getClass().getSimpleName()).append(';');
        }
    }

    private static void checkEgressHost(ClassLoader loader, HookConfig config, boolean killMode,
                                        String rawHost, XC_MethodHook.MethodHookParam param,
                                        Object blockResult, Throwable blockThrowable) {
        String host = BodyguardEventCodec.normalizeHost(rawHost);
        // Gate 8 (IP_FLIP): zero-window check for external daemon lock or dropped VPN tunnel
        // before any outbound non-local request can leave the process.
        if (!BodyguardEventCodec.isLocalOrNonEgressHost(host)) {
            refreshIpGuardCache(config.targetId);
            if (ipCachedLocked && killMode) {
                if (blockThrowable != null) {
                    param.setThrowable(blockThrowable);
                } else {
                    param.setResult(blockResult);
                }
                Process.killProcess(Process.myPid());
                return;
            }
            if (config.bgIpKillOnDrop && ipCachedExpectTun && !ipCachedTunAlive) {
                IN_GUARD.set(Boolean.TRUE);
                try {
                    emitAndMaybeKill(loader, config, killMode,
                            BodyguardPolicy.GATE_IP_FLIP, "net:vpn-down", "net",
                            param, blockResult, blockThrowable);
                } finally {
                    IN_GUARD.remove();
                }
                return;
            }
        }
        if (config.bgAllowHosts == null || config.bgAllowHosts.isEmpty()
                || BodyguardEventCodec.isHostAllowed(host, config.bgAllowHosts, config.bgAllowCustom)) {
            return;
        }
        IN_GUARD.set(Boolean.TRUE);
        try {
            String caller = findTargetCaller();
            emitAndMaybeKill(loader, config, killMode,
                    BodyguardPolicy.GATE_EGRESS_HOST, "host:" + host,
                    caller.isEmpty() ? "net" : caller,
                    param, blockResult, blockThrowable);
        } finally {
            IN_GUARD.remove();
        }
    }

    /** True when the external lock file is strictly newer than any unlock file. */
    private static boolean isExternalLockActive(String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            return false;
        }
        try {
            File lf = new File(IpGuardPolicy.LOCK_DIR, targetId + ".lock");
            File uf = new File(IpGuardPolicy.LOCK_DIR, targetId + ".unlock");
            long lt = lf.isFile() ? lf.lastModified() : 0L;
            long ut = uf.isFile() ? uf.lastModified() : 0L;
            return BodyguardPolicy.isLockEffective(0L, lt, ut);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Enforces an active Bodyguard lock inside the target process at Application.attach so a
     * locked target cannot start even when system_server is not enabled in Vector/LSPosed scope.
     */
    private static void installBootLockGate(final ClassLoader loader) {
        final java.util.concurrent.atomic.AtomicBoolean killed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        XC_MethodHook gate = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (killed.compareAndSet(false, true)) {
                    Process.killProcess(Process.myPid());
                }
            }
        };
        try {
            Class<?> appCls = XposedHelpers.findClassIfExists("android.app.Application", loader);
            if (appCls != null) {
                XposedBridge.hookAllMethods(appCls, "attach", gate);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> instCls = XposedHelpers.findClassIfExists("android.app.Instrumentation", loader);
            if (instCls != null) {
                XposedBridge.hookAllMethods(instCls, "callApplicationOnCreate", gate);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Refreshes lock-file and tunnel-interface state at most every 250ms using pure filesystem reads. */
    private static void refreshIpGuardCache(String targetId) {
        long now = System.currentTimeMillis();
        if ((now - ipCheckLastMs) < 250L) {
            return;
        }
        ipCheckLastMs = now;
        try {
            if (targetId != null && !targetId.isEmpty()) {
                File lf = new File(IpGuardPolicy.LOCK_DIR, targetId + ".lock");
                File uf = new File(IpGuardPolicy.LOCK_DIR, targetId + ".unlock");
                long lt = lf.isFile() ? lf.lastModified() : 0L;
                long ut = uf.isFile() ? uf.lastModified() : 0L;
                ipCachedLocked = BodyguardPolicy.isLockEffective(0L, lt, ut);
            } else {
                ipCachedLocked = false;
            }
        } catch (Throwable ignored) {
            ipCachedLocked = false;
        }
        boolean expectTun = false;
        try {
            File conf = new File(IpGuardPolicy.CONF_PATH);
            if (conf.isFile() && conf.canRead()) {
                java.io.FileInputStream in = new java.io.FileInputStream(conf);
                try {
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);
                    if (n > 0) {
                        String text = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                        expectTun = text.contains("EXPECT_TUN=1");
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignored) {
        }
        ipCachedExpectTun = expectTun;
        if (!expectTun) {
            ipCachedTunAlive = true;
            return;
        }
        boolean tunUp = false;
        try {
            String[] ifaces = new File("/sys/class/net").list();
            if (ifaces != null) {
                for (String name : ifaces) {
                    if (IpGuardPolicy.isTunnelInterfaceName(name)) {
                        File op = new File("/sys/class/net/" + name + "/operstate");
                        String st = readSmallText(op);
                        if (!"down".equalsIgnoreCase(st)) {
                            tunUp = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            tunUp = true;
        }
        ipCachedTunAlive = tunUp;
    }

    private static String readSmallText(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return "";
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Pre-loads Bodyguard helper classes at target boot to prevent lazy DEX load after APK upgrade. */
    private static void warmUpClasses() {
        try {
            byte[] probe = BodyguardEventCodec.encode(
                    "OBSERVED", BodyguardPolicy.GATE_SYS_PROP, "warmup", "init", "0");
            BodyguardEventCodec.isBodyguardSummary(probe);
            BodyguardEventCodec.isSensitivePropKey("ro.boot.vbmeta.digest");
            BodyguardEventCodec.isFrameworkFrame("android.os.Build");
            BodyguardEventCodec.isBuiltinAllowedSettingsKey("android_id");
            BodyguardEventCodec.isTrustedDynamicLoaderCaller("com.google.android.gms.chimera");
            BodyguardEventCodec.abbreviateCaller("a.b.C", "m");
            BodyguardEventCodec.isHostAllowed("api.anthropic.com", null, null);
            IpGuardPolicy.isTunnelInterfaceName("tun0");
            Process.myPid();
        } catch (Throwable ignored) {
        }
    }

    // ---- In-Memory ExitInfo Stealth (Zero Disk Residue) --------------------------------

    /**
     * Pure in-memory filter hiding Bodyguard's own {@code CWBG1|} {@code ApplicationExitInfo}
     * records from target-process crash/telemetry SDKs (Sentry, Datadog, Crashlytics) when they
     * query {@code ActivityManager.getHistoricalProcessExitReasons} on subsequent launches.
     * System-side AMS records stay untouched so ClaudeWilson's external read-only collector still
     * reads them normally.
     */
    private static void installExitInfoStealth(ClassLoader loader) {
        try {
            Class<?> am = XposedHelpers.findClassIfExists("android.app.ActivityManager", loader);
            if (am != null) {
                XposedBridge.hookAllMethods(am, "getHistoricalProcessExitReasons",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                Object res = param.getResult();
                                if (!(res instanceof java.util.List)) {
                                    return;
                                }
                                java.util.List<?> orig = (java.util.List<?>) res;
                                if (orig.isEmpty()) {
                                    return;
                                }
                                IN_EXIT_FILTER.set(Boolean.TRUE);
                                try {
                                    java.util.List<Object> kept =
                                            new java.util.ArrayList<Object>(orig.size());
                                    boolean dropped = false;
                                    for (Object item : orig) {
                                        if (item == null) {
                                            continue;
                                        }
                                        Object sum = XposedHelpers.callMethod(
                                                item, "getProcessStateSummary");
                                        if (sum instanceof byte[]
                                                && BodyguardEventCodec.isBodyguardSummary(
                                                        (byte[]) sum)) {
                                            dropped = true;
                                            continue;
                                        }
                                        kept.add(item);
                                    }
                                    if (dropped) {
                                        param.setResult(kept);
                                    }
                                } catch (Throwable ignored) {
                                } finally {
                                    IN_EXIT_FILTER.remove();
                                }
                            }
                        });
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> aei = XposedHelpers.findClassIfExists(
                    "android.app.ApplicationExitInfo", loader);
            if (aei != null) {
                XposedBridge.hookAllMethods(aei, "getProcessStateSummary",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (Boolean.TRUE.equals(IN_EXIT_FILTER.get())) {
                                    return;
                                }
                                Object res = param.getResult();
                                if (res instanceof byte[]
                                        && BodyguardEventCodec.isBodyguardSummary((byte[]) res)) {
                                    param.setResult(null);
                                }
                            }
                        });
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Shared Incident Reporting (Zero Target Files) ---------------------------------

    private static void emitAndMaybeKill(ClassLoader loader, HookConfig config, boolean killMode,
                                         String gate, String signalKey, String callerShort,
                                         XC_MethodHook.MethodHookParam param,
                                         Object blockResult, Throwable blockThrowable) {
        String action = killMode ? "KILLED" : "OBSERVED";
        byte[] summary = BodyguardEventCodec.encode(
                action, gate, signalKey, callerShort, config.bodyguardVersion);
        writeProcessStateSummary(loader, summary);

        if (HookEntry.debugLog) {
            XposedBridge.log("ClaudeWilsonHook bodyguard " + action + " " + gate
                    + " " + signalKey + " caller=" + callerShort);
        }

        if (killMode) {
            if (blockThrowable != null) {
                param.setThrowable(blockThrowable);
            } else {
                param.setResult(blockResult);
            }
            Process.killProcess(Process.myPid());
        }
    }

    /** Writes <= 128 bytes into Android AMS ApplicationExitInfo (zero files in target dir). */
    private static void writeProcessStateSummary(ClassLoader loader, byte[] summary) {
        try {
            Context ctx = HookContext.app(loader);
            if (ctx == null) {
                return;
            }
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.setProcessStateSummary(summary);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Returns the first non-framework caller frame, or "" if purely Android framework internal. */
    private static String findTargetCaller() {
        return findTargetCaller(false);
    }

    private static String findTargetCaller(boolean skipTrustedDynamicLoaders) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack == null) {
            return "";
        }
        for (int i = 3; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            if (BodyguardEventCodec.isFrameworkFrame(cls)) {
                continue;
            }
            if (skipTrustedDynamicLoaders
                    && BodyguardEventCodec.isTrustedDynamicLoaderCaller(cls)) {
                return "";
            }
            return BodyguardEventCodec.abbreviateCaller(cls, stack[i].getMethodName());
        }
        return "";
    }

    private static String extractExecTag(Object cmdObj) {
        String first = null;
        if (cmdObj instanceof String) {
            String s = ((String) cmdObj).trim();
            int sp = s.indexOf(' ');
            first = sp > 0 ? s.substring(0, sp) : s;
        } else if (cmdObj instanceof String[]) {
            String[] arr = (String[]) cmdObj;
            if (arr.length > 0 && arr[0] != null) {
                first = arr[0].trim();
            }
        } else if (cmdObj instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) cmdObj;
            if (!list.isEmpty() && list.get(0) != null) {
                first = String.valueOf(list.get(0)).trim();
            }
        }
        if (first == null || first.isEmpty()) {
            return "";
        }
        int slash = first.lastIndexOf('/');
        String base = slash >= 0 && slash + 1 < first.length()
                ? first.substring(slash + 1) : first;
        return base.length() > 16 ? base.substring(0, 16) : base;
    }

    private static boolean isTrustedDexPath(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        return path.startsWith("/system/")
                || path.startsWith("/apex/")
                || path.startsWith("/data/app/")
                || path.contains("com.google.android.gms")
                || path.contains("app_chimera")
                || path.contains("dynamite");
    }
}

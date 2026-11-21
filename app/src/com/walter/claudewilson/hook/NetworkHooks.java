package com.walter.claudewilson.hook;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.text.TextUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides tunnel interfaces, VPN transport, system proxy, and synthesizes per-interface
 * regional IPv6 addresses across NetworkInterface and LinkProperties (WiFi vs Cellular).
 */
final class NetworkHooks {
    private static final int TRANSPORT_VPN = NetworkCapabilities.TRANSPORT_VPN;
    private static final int CAPABILITY_NOT_VPN = NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
    private static final int TYPE_WIFI = 1;
    private static final int TYPE_VPN = 17;
    private static final byte[] PUBLIC_FALLBACK_DNS_V4 = new byte[]{1, 1, 1, 1};

    /** Primary synthesized IPv6 address for selfcheck verification. */
    private static volatile InetAddress spoofedV6;

    private static volatile boolean cfgIpv6Disabled;
    private static volatile String cfgPrefix = "";
    private static volatile String cfgMobilePrefix = "";
    private static volatile long cfgSeed;
    private static volatile Set<String> cfgHiddenIfaces = Collections.emptySet();
    private static volatile byte[] cachedBootId = new byte[0];

    private static final ConcurrentHashMap<String, InetAddress> ifaceCache =
            new ConcurrentHashMap<>();

    /**
     * Reentrant depth counter active inside LinkProperties internal mutators (addRoute,
     * removeRoute, addStackedLink — called during Parcel unmarshaling and copy construction).
     * While > 0, RouteInfo.getInterface() and LinkProperties.getInterfaceName() return the raw
     * interface name so Android's "Route added with non-matching interface" check never throws.
     */
    private static final ThreadLocal<Integer> LP_INTERNAL_DEPTH = new ThreadLocal<Integer>();

    private static void enterLpInternal() {
        Integer cur = LP_INTERNAL_DEPTH.get();
        LP_INTERNAL_DEPTH.set(cur == null ? 1 : cur + 1);
    }

    private static void exitLpInternal() {
        Integer cur = LP_INTERNAL_DEPTH.get();
        if (cur == null || cur <= 1) {
            LP_INTERNAL_DEPTH.remove();
        } else {
            LP_INTERNAL_DEPTH.set(cur - 1);
        }
    }

    private static boolean inLpInternal() {
        Integer cur = LP_INTERNAL_DEPTH.get();
        return cur != null && cur > 0;
    }

    private NetworkHooks() {}

    static InetAddress spoofedAddress() {
        return spoofedV6;
    }

    /** Chooses mobile carrier prefix for cellular interfaces, WiFi/default prefix otherwise. */
    private static String prefixForInterface(String ifaceName) {
        if (ifaceName != null && isCellularName(ifaceName)) {
            if (!cfgMobilePrefix.isEmpty()) return cfgMobilePrefix;
        }
        return !cfgPrefix.isEmpty() ? cfgPrefix : cfgMobilePrefix;
    }

    /** Generates a deterministic per-interface IPv6 address (WiFi pool vs Cellular pool). */
    static InetAddress spoofForInterface(String ifaceName) {
        if (ifaceName == null || ifaceName.isEmpty()) return spoofedV6;
        String key = badName(ifaceName) ? "wlan0" : ifaceName;
        String prefix = prefixForInterface(key);
        if (prefix.isEmpty()) return spoofedV6;
        InetAddress cached = ifaceCache.get(key);
        if (cached != null) return cached;
        try {
            byte[] addr = generateV6Bytes(prefix, cfgSeed, key);
            if (addr != null) {
                cached = InetAddress.getByAddress(addr);
                ifaceCache.put(key, cached);
                return cached;
            }
        } catch (Throwable ignored) {}
        return spoofedV6;
    }

    /** Returns the wlan0 per-interface address for WebRTC/external consumers, or primary. */
    static InetAddress spoofForDefaultInterface() {
        if (cfgPrefix.isEmpty() && cfgMobilePrefix.isEmpty()) return spoofedV6;
        return spoofForInterface("wlan0");
    }

    static void install(ClassLoader loader, HookConfig config) {
        ifaceCache.clear();
        spoofedV6 = null;
        cfgPrefix = "";
        cfgMobilePrefix = "";
        cfgSeed = 0L;
        Set<String> dynHidden = new LinkedHashSet<>();
        if (config != null && config.hiddenIfaces != null) {
            for (String h : config.hiddenIfaces) {
                if (h != null && !h.trim().isEmpty()) {
                    dynHidden.add(h.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        cfgHiddenIfaces = Collections.unmodifiableSet(dynHidden);
        cfgIpv6Disabled = config != null && config.ipv6Disabled;
        cfgSeed = 0L;

        if (!cfgIpv6Disabled && config != null && (!config.ipv6Prefix.isEmpty()
                || !config.ipv6MobilePrefix.isEmpty())) {
            cfgPrefix = config.ipv6Prefix;
            cfgMobilePrefix = !config.ipv6MobilePrefix.isEmpty()
                    ? config.ipv6MobilePrefix : config.ipv6Prefix;
            if (cfgPrefix.isEmpty()) {
                cfgPrefix = cfgMobilePrefix;
            }
            cfgSeed = config.ipv6Seed;
            try {
                cachedBootId = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id")), "UTF-8")
                        .trim().getBytes("UTF-8");
            } catch (Throwable t) {
                cachedBootId = new byte[0];
            }
            try {
                byte[] addr = generateV6Bytes(cfgPrefix, cfgSeed, null);
                if (addr != null) {
                    spoofedV6 = InetAddress.getByAddress(addr);
                }
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook ipv6gen: " + t);
            }
        }

        // ---- NetworkInterface: drop tunnel interfaces in enumeration + getByName ----
        try {
            Class<?> ni = XposedHelpers.findClass("java.net.NetworkInterface", loader);
            XposedBridge.hookAllMethods(ni, "getNetworkInterfaces",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof Enumeration)) return;
                            List<NetworkInterface> keep = new ArrayList<>();
                            Enumeration<?> all = (Enumeration<?>) result;
                            while (all.hasMoreElements()) {
                                Object next = all.nextElement();
                                if (next instanceof NetworkInterface
                                        && !isHiddenInterface((NetworkInterface) next)) {
                                    keep.add((NetworkInterface) next);
                                }
                            }
                            param.setResult(Collections.enumeration(keep));
                        }
                    });
            XposedBridge.hookAllMethods(ni, "getByName",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof String
                                    && badName((String) param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook interfaces: " + t);
        }

        // ---- NetworkInterface.getInetAddresses: per-interface WiFi vs Cellular IPv6 ----
        try {
            Class<?> ni2 = XposedHelpers.findClass("java.net.NetworkInterface", loader);
            XposedBridge.hookAllMethods(ni2, "getInetAddresses",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof Enumeration)) return;
                            String ifName = null;
                            if (param.thisObject instanceof NetworkInterface) {
                                ifName = ((NetworkInterface) param.thisObject).getName();
                            }
                            InetAddress ifSpoof = ifName != null
                                    ? spoofForInterface(ifName) : spoofedV6;
                            List<InetAddress> keep = new ArrayList<>();
                            boolean v6Injected = false;
                            boolean hasNonLoopback = false;
                            Enumeration<?> all = (Enumeration<?>) result;
                            while (all.hasMoreElements()) {
                                Object next = all.nextElement();
                                if (next instanceof InetAddress) {
                                    InetAddress addr = (InetAddress) next;
                                    if (addr instanceof Inet4Address
                                            && !addr.isLoopbackAddress()
                                            && !addr.isLinkLocalAddress()
                                            && !is464Xlat(addr)) {
                                        hasNonLoopback = true;
                                    }
                                    if (addr instanceof Inet6Address && isGlobalUnicast(addr)) {
                                        if (cfgIpv6Disabled) {
                                            continue;
                                        }
                                        if (ifSpoof != null) {
                                            if (!v6Injected) {
                                                keep.add(ifSpoof);
                                                v6Injected = true;
                                            }
                                            continue;
                                        }
                                    } else if (cfgIpv6Disabled && addr instanceof Inet6Address
                                            && isUlaV6(addr)) {
                                        continue;
                                    }
                                    keep.add(addr);
                                }
                            }
                            if (!v6Injected && ifSpoof != null && hasNonLoopback) {
                                keep.add(ifSpoof);
                            }
                            param.setResult(Collections.enumeration(keep));
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook inet6spoof: " + t);
        }

        // ---- NetworkInterface.getInterfaceAddresses: agree with getInetAddresses ----
        try {
            Class<?> ni3 = XposedHelpers.findClass("java.net.NetworkInterface", loader);
            final Constructor<InterfaceAddress> iaCtor =
                    InterfaceAddress.class.getDeclaredConstructor();
            iaCtor.setAccessible(true);
            final Field iaAddr = InterfaceAddress.class.getDeclaredField("address");
            iaAddr.setAccessible(true);
            final Field iaMask = InterfaceAddress.class.getDeclaredField("maskLength");
            iaMask.setAccessible(true);

            XposedBridge.hookAllMethods(ni3, "getInterfaceAddresses",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof List)) return;
                            String ifName = null;
                            if (param.thisObject instanceof NetworkInterface) {
                                ifName = ((NetworkInterface) param.thisObject).getName();
                            }
                            InetAddress ifSpoof = ifName != null
                                    ? spoofForInterface(ifName) : spoofedV6;
                            @SuppressWarnings("unchecked")
                            List<InterfaceAddress> orig = (List<InterfaceAddress>) result;
                            List<InterfaceAddress> keep = new ArrayList<>();
                            boolean v6Replaced = false;
                            boolean hasRoutableV4 = false;
                            for (InterfaceAddress ia : orig) {
                                if (ia == null) continue;
                                InetAddress addr = ia.getAddress();
                                if (addr instanceof Inet4Address
                                        && !addr.isLoopbackAddress()
                                        && !addr.isLinkLocalAddress()
                                        && !is464Xlat(addr)) {
                                    hasRoutableV4 = true;
                                }
                                if (addr instanceof Inet6Address && isGlobalUnicast(addr)) {
                                    if (cfgIpv6Disabled) {
                                        continue;
                                    }
                                    if (ifSpoof != null) {
                                        if (!v6Replaced) {
                                            try {
                                                InterfaceAddress fake = iaCtor.newInstance();
                                                iaAddr.set(fake, ifSpoof);
                                                iaMask.setShort(fake, (short) 64);
                                                keep.add(fake);
                                                v6Replaced = true;
                                            } catch (Throwable ignored) {}
                                        }
                                        continue;
                                    }
                                } else if (cfgIpv6Disabled && addr instanceof Inet6Address
                                        && isUlaV6(addr)) {
                                    continue;
                                }
                                keep.add(ia);
                            }
                            if (!v6Replaced && ifSpoof != null && hasRoutableV4) {
                                try {
                                    InterfaceAddress fake = iaCtor.newInstance();
                                    iaAddr.set(fake, ifSpoof);
                                    iaMask.setShort(fake, (short) 64);
                                    keep.add(fake);
                                } catch (Throwable ignored) {}
                            }
                            param.setResult(keep);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook interfaceAddresses: " + t);
        }

        // ---- LinkProperties & RouteInfo: interface names, LinkAddresses, InetAddresses, routes ----
        installLinkPropertiesHooks(loader);

        // ---- NetworkCapabilities: hide VPN transport, VpnTransportInfo & toString leaks ----
        try {
            Class<?> nc = XposedHelpers.findClass("android.net.NetworkCapabilities", loader);
            XposedBridge.hookAllMethods(nc, "hasTransport",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && Integer.valueOf(TRANSPORT_VPN)
                                    .equals(param.args[0])) {
                                param.setResult(false);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(nc, "hasCapability",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && Integer.valueOf(CAPABILITY_NOT_VPN)
                                    .equals(param.args[0])) {
                                param.setResult(true);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(nc, "getCapabilities",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (!(value instanceof int[])) return;
                            int[] caps = (int[]) value;
                            for (int c : caps) {
                                if (c == CAPABILITY_NOT_VPN) return;
                            }
                            int[] out = new int[caps.length + 1];
                            System.arraycopy(caps, 0, out, 0, caps.length);
                            out[caps.length] = CAPABILITY_NOT_VPN;
                            param.setResult(out);
                        }
                    });
            XposedBridge.hookAllMethods(nc, "getTransportTypes",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (!(value instanceof int[])) return;
                            int[] types = (int[]) value;
                            int count = 0;
                            for (int type : types) if (type != TRANSPORT_VPN) count++;
                            int[] out = new int[count];
                            int i = 0;
                            for (int type : types) if (type != TRANSPORT_VPN) out[i++] = type;
                            param.setResult(out);
                        }
                    });
            XposedBridge.hookAllMethods(nc, "getTransportInfo",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (value != null
                                    && value.getClass().getName().contains("VpnTransportInfo")) {
                                param.setResult(null);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(nc, "toString",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (value instanceof String) {
                                String s = (String) value;
                                if (s.contains("VPN") || s.contains("VpnTransportInfo")) {
                                    param.setResult(com.walter.claudewilson.model.NetworkRules
                                            .sanitizeCapabilitiesString(s));
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook networkcaps: " + t);
        }

        // ---- ConnectivityManager & NetworkInfo: hide default proxy & legacy TYPE_VPN ----
        try {
            Class<?> cm = XposedHelpers.findClass("android.net.ConnectivityManager", loader);
            XposedBridge.hookAllMethods(cm, "getDefaultProxy", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    return null;
                }
            });
            XposedBridge.hookAllMethods(cm, "getNetworkInfo",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 1
                                    && Integer.valueOf(TYPE_VPN).equals(param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook proxy: " + t);
        }

        try {
            Class<?> niInfo = XposedHelpers.findClass("android.net.NetworkInfo", loader);
            XposedBridge.hookAllMethods(niInfo, "getType",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (Integer.valueOf(TYPE_VPN).equals(param.getResult())) {
                                param.setResult(TYPE_WIFI);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(niInfo, "getTypeName",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (value instanceof String
                                    && "VPN".equalsIgnoreCase(((String) value).trim())) {
                                param.setResult("WIFI");
                            }
                        }
                    });
            XposedBridge.hookAllMethods(niInfo, "getExtraInfo",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (value instanceof String) {
                                String low = ((String) value).trim().toLowerCase(Locale.ROOT);
                                if (low.startsWith("vpn:") || "vpn".equals(low)
                                        || badName(low)) {
                                    param.setResult(null);
                                }
                            }
                        }
                    });
            XposedBridge.hookAllMethods(niInfo, "toString",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (value instanceof String && ((String) value).contains("VPN")) {
                                param.setResult(com.walter.claudewilson.model.NetworkRules
                                        .sanitizeNetworkInfoString((String) value));
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook networkinfo: " + t);
        }

        // ---- System.getProperty: hide proxy system properties ----
        try {
            XposedBridge.hookAllMethods(System.class, "getProperty",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0) return;
                            String key = String.valueOf(param.args[0]);
                            if (key.matches("(?i)^(https?|socks)\\.proxy.*")) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook sysprops: " + t);
        }
    }

    private static void installLinkPropertiesHooks(ClassLoader loader) {
        try {
            final Class<?> lpClass = XposedHelpers.findClass("android.net.LinkProperties", loader);
            final Class<?> laClass = XposedHelpers.findClass("android.net.LinkAddress", loader);
            final Class<?> ipPrefixClass = XposedHelpers.findClass("android.net.IpPrefix", loader);

            Field ifaceField = null;
            try {
                ifaceField = lpClass.getDeclaredField("mIfaceName");
                ifaceField.setAccessible(true);
            } catch (Throwable ignored) {}
            final Field lpIfaceField = ifaceField;

            Constructor<?> ctor2 = null;
            Constructor<?> ctor4 = null;
            try {
                ctor2 = laClass.getDeclaredConstructor(InetAddress.class, int.class);
                ctor2.setAccessible(true);
            } catch (Throwable ignored) {}
            if (ctor2 == null) {
                try {
                    ctor4 = laClass.getDeclaredConstructor(
                            InetAddress.class, int.class, int.class, int.class);
                    ctor4.setAccessible(true);
                } catch (Throwable ignored) {}
            }
            final Constructor<?> laCtor2 = ctor2;
            final Constructor<?> laCtor4 = ctor4;

            Constructor<?> pfxCtor = null;
            try {
                pfxCtor = ipPrefixClass.getDeclaredConstructor(InetAddress.class, int.class);
                pfxCtor.setAccessible(true);
            } catch (Throwable ignored) {}
            final Constructor<?> ipPfxCtor = pfxCtor;

            // 0. Guard internal LinkProperties mutators (used during Parcel unmarshaling & copy)
            //    so RouteInfo.getInterface() matches raw mIfaceName ("tun0" vs "tun0") internally.
            XC_MethodHook lpInternalGuard = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    enterLpInternal();
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    exitLpInternal();
                }
            };
            XposedBridge.hookAllMethods(lpClass, "addRoute", lpInternalGuard);
            XposedBridge.hookAllMethods(lpClass, "removeRoute", lpInternalGuard);
            XposedBridge.hookAllMethods(lpClass, "addStackedLink", lpInternalGuard);

            // 1. Interface names
            XposedBridge.hookAllMethods(lpClass, "getInterfaceName",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (inLpInternal()) return;
                            Object value = param.getResult();
                            if (value instanceof String && badName((String) value)) {
                                param.setResult("wlan0");
                            }
                        }
                    });
            XposedBridge.hookAllMethods(lpClass, "getAllInterfaceNames",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (!(value instanceof List)) return;
                            @SuppressWarnings("unchecked")
                            List<String> orig = (List<String>) value;
                            Set<String> dedup = new LinkedHashSet<>();
                            for (String n : orig) {
                                if (n == null) continue;
                                dedup.add(badName(n) ? "wlan0" : n);
                            }
                            param.setResult(new ArrayList<>(dedup));
                        }
                    });

            // 2. HTTP proxy
            XposedBridge.hookAllMethods(lpClass, "getHttpProxy", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    return null;
                }
            });

            // 3. LinkAddress lists: getLinkAddresses & getAllLinkAddresses
            XC_MethodHook linkAddrHook = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (!(value instanceof List)) return;
                    String realIface = readRawIface(param.thisObject, lpIfaceField);
                    boolean isTunnel = badName(realIface);
                    String effIface = isTunnel ? "wlan0"
                            : (realIface != null && !realIface.isEmpty() ? realIface : "wlan0");
                    InetAddress ifSpoof = spoofForInterface(effIface);

                    @SuppressWarnings("unchecked")
                    List<LinkAddress> orig = (List<LinkAddress>) value;
                    List<LinkAddress> keep = new ArrayList<>(orig.size() + 1);
                    boolean v6Injected = false;
                    boolean v4Replaced = false;
                    boolean hasRoutableV4 = false;

                    for (LinkAddress la : orig) {
                        if (la == null) continue;
                        InetAddress addr = la.getAddress();
                        if (addr == null) continue;
                        if (addr instanceof Inet6Address) {
                            if (isGlobalUnicast(addr)) {
                                if (cfgIpv6Disabled) {
                                    continue;
                                }
                                if (ifSpoof != null) {
                                    if (!v6Injected) {
                                        LinkAddress fake = buildLinkAddress(
                                                laCtor2, laCtor4, ifSpoof, 64);
                                        if (fake != null) {
                                            keep.add(fake);
                                            v6Injected = true;
                                        }
                                    }
                                    continue;
                                }
                            } else if ((cfgIpv6Disabled || isTunnel) && isUlaV6(addr)) {
                                continue;
                            }
                        } else if (addr instanceof Inet4Address) {
                            if (isTunnel && !addr.isLoopbackAddress()) {
                                if (!v4Replaced) {
                                    LinkAddress lanV4 = fallbackLanV4LinkAddress(laCtor2, laCtor4);
                                    if (lanV4 != null) {
                                        keep.add(lanV4);
                                        hasRoutableV4 = true;
                                        v4Replaced = true;
                                    }
                                }
                                continue;
                            }
                            if (!addr.isLoopbackAddress()
                                    && !addr.isLinkLocalAddress()
                                    && !is464Xlat(addr)) {
                                hasRoutableV4 = true;
                            }
                        }
                        keep.add(la);
                    }

                    if (!v6Injected && ifSpoof != null && (hasRoutableV4 || isTunnel)) {
                        LinkAddress fake = buildLinkAddress(laCtor2, laCtor4, ifSpoof, 64);
                        if (fake != null) {
                            keep.add(fake);
                        }
                    }
                    param.setResult(keep);
                }
            };
            XposedBridge.hookAllMethods(lpClass, "getLinkAddresses", linkAddrHook);
            XposedBridge.hookAllMethods(lpClass, "getAllLinkAddresses", linkAddrHook);

            // 4. InetAddress lists: getAddresses & getAllAddresses
            XC_MethodHook inetAddrHook = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (!(value instanceof List)) return;
                    String realIface = readRawIface(param.thisObject, lpIfaceField);
                    boolean isTunnel = badName(realIface);
                    String effIface = isTunnel ? "wlan0"
                            : (realIface != null && !realIface.isEmpty() ? realIface : "wlan0");
                    InetAddress ifSpoof = spoofForInterface(effIface);

                    @SuppressWarnings("unchecked")
                    List<InetAddress> orig = (List<InetAddress>) value;
                    List<InetAddress> keep = new ArrayList<>(orig.size() + 1);
                    boolean v6Injected = false;
                    boolean v4Replaced = false;
                    boolean hasRoutableV4 = false;

                    for (InetAddress addr : orig) {
                        if (addr == null) continue;
                        if (addr instanceof Inet6Address) {
                            if (isGlobalUnicast(addr)) {
                                if (cfgIpv6Disabled) {
                                    continue;
                                }
                                if (ifSpoof != null) {
                                    if (!v6Injected) {
                                        keep.add(ifSpoof);
                                        v6Injected = true;
                                    }
                                    continue;
                                }
                            } else if ((cfgIpv6Disabled || isTunnel) && isUlaV6(addr)) {
                                continue;
                            }
                        } else if (addr instanceof Inet4Address) {
                            if (isTunnel && !addr.isLoopbackAddress()) {
                                if (!v4Replaced) {
                                    InetAddress lan = fallbackLanV4Address();
                                    if (lan != null) {
                                        keep.add(lan);
                                        hasRoutableV4 = true;
                                        v4Replaced = true;
                                    }
                                }
                                continue;
                            }
                            if (!addr.isLoopbackAddress()
                                    && !addr.isLinkLocalAddress()
                                    && !is464Xlat(addr)) {
                                hasRoutableV4 = true;
                            }
                        }
                        keep.add(addr);
                    }

                    if (!v6Injected && ifSpoof != null && (hasRoutableV4 || isTunnel)) {
                        keep.add(ifSpoof);
                    }
                    param.setResult(keep);
                }
            };
            XposedBridge.hookAllMethods(lpClass, "getAddresses", inetAddrHook);
            XposedBridge.hookAllMethods(lpClass, "getAllAddresses", inetAddrHook);

            // 4b. DNS servers & MTU on tunnel LinkProperties (hides 172.19.0.2 / 198.18.0.2 / MTU 9000)
            XposedBridge.hookAllMethods(lpClass, "getDnsServers",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (!(value instanceof List)) return;
                            String realIface = readRawIface(param.thisObject, lpIfaceField);
                            boolean isTunnel = badName(realIface);
                            if (!isTunnel && !cfgIpv6Disabled) return;

                            @SuppressWarnings("unchecked")
                            List<InetAddress> orig = (List<InetAddress>) value;
                            if (orig.isEmpty()) return;
                            List<InetAddress> keep = new ArrayList<>(orig.size());
                            boolean addedFallback = false;
                            for (InetAddress addr : orig) {
                                if (addr == null) continue;
                                if (addr instanceof Inet6Address) {
                                    if (cfgIpv6Disabled
                                            && (isGlobalUnicast(addr) || isUlaV6(addr))) {
                                        continue;
                                    }
                                    if (isTunnel && isUlaV6(addr)) {
                                        continue;
                                    }
                                    keep.add(addr);
                                } else if (addr instanceof Inet4Address) {
                                    if (isTunnel && com.walter.claudewilson.model.NetworkRules
                                            .isTunnelPeerOrPrivateDns(addr.getAddress())) {
                                        if (!addedFallback) {
                                            InetAddress pub = publicFallbackDnsV4();
                                            if (pub != null) {
                                                keep.add(pub);
                                                addedFallback = true;
                                            }
                                        }
                                        continue;
                                    }
                                    keep.add(addr);
                                }
                            }
                            if (keep.isEmpty() && isTunnel) {
                                InetAddress pub = publicFallbackDnsV4();
                                if (pub != null) {
                                    keep.add(pub);
                                }
                            }
                            param.setResult(keep);
                        }
                    });

            XposedBridge.hookAllMethods(lpClass, "getMtu",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.getResult();
                            if (!(value instanceof Integer)) return;
                            String realIface = readRawIface(param.thisObject, lpIfaceField);
                            if (!badName(realIface)) return;
                            int mtu = (Integer) value;
                            if (mtu > 1500 || (mtu > 0 && mtu < 1500)) {
                                param.setResult(1500);
                            }
                        }
                    });

            // 5. Routes & RouteInfo.getInterface
            Class<?> riClass = XposedHelpers.findClass("android.net.RouteInfo", loader);
            Constructor<?> riCtor3 = null;
            Constructor<?> riCtor4 = null;
            try {
                riCtor3 = riClass.getDeclaredConstructor(
                        laClass, InetAddress.class, String.class);
                riCtor3.setAccessible(true);
            } catch (Throwable ignored) {}
            try {
                riCtor4 = riClass.getDeclaredConstructor(
                        ipPrefixClass, InetAddress.class, String.class, int.class);
                riCtor4.setAccessible(true);
            } catch (Throwable ignored) {}
            final Constructor<?> routeCtor3 = riCtor3;
            final Constructor<?> routeCtor4 = riCtor4;

            XposedBridge.hookAllMethods(riClass, "getInterface",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (inLpInternal()) return;
                            Object value = param.getResult();
                            if (value instanceof String && badName((String) value)) {
                                param.setResult("wlan0");
                            }
                        }
                    });

            XC_MethodHook routesHook = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (!(value instanceof List)) return;
                    String realIface = readRawIface(param.thisObject, lpIfaceField);
                    boolean isTunnel = badName(realIface);
                    String effIface = isTunnel ? "wlan0"
                            : (realIface != null && !realIface.isEmpty() ? realIface : "wlan0");
                    InetAddress ifSpoof = spoofForInterface(effIface);

                    @SuppressWarnings("unchecked")
                    List<RouteInfo> orig = (List<RouteInfo>) value;
                    List<RouteInfo> keep = new ArrayList<>(orig.size() + 1);
                    boolean v6RouteAdded = false;
                    boolean v4LanRouteAdded = false;

                    for (RouteInfo r : orig) {
                        if (r == null) continue;
                        IpPrefix dest = r.getDestination();
                        InetAddress destAddr = dest != null ? dest.getAddress() : null;
                        int pfxLen = dest != null ? dest.getPrefixLength() : 0;
                        if (destAddr instanceof Inet6Address) {
                            if (cfgIpv6Disabled && isTunnel) {
                                continue;
                            }
                            if (pfxLen > 0) {
                                if (isGlobalUnicast(destAddr)) {
                                    if (cfgIpv6Disabled) {
                                        continue;
                                    }
                                    if (ifSpoof != null) {
                                        if (!v6RouteAdded) {
                                            RouteInfo fakeRoute = buildSpoofRoute(
                                                    routeCtor3, routeCtor4, ipPfxCtor,
                                                    laCtor2, laCtor4, ifSpoof, effIface);
                                            if (fakeRoute != null) {
                                                keep.add(fakeRoute);
                                                v6RouteAdded = true;
                                            }
                                        }
                                        continue;
                                    }
                                } else if ((cfgIpv6Disabled || isTunnel) && isUlaV6(destAddr)) {
                                    continue;
                                }
                            }
                        } else if (isTunnel && pfxLen > 0 && destAddr instanceof Inet4Address) {
                            if (!v4LanRouteAdded) {
                                RouteInfo lanRoute = buildLanV4Route(
                                        routeCtor3, routeCtor4, ipPfxCtor,
                                        laCtor2, laCtor4, effIface);
                                if (lanRoute != null) {
                                    keep.add(lanRoute);
                                    v4LanRouteAdded = true;
                                }
                            }
                            continue;
                        }
                        keep.add(r);
                    }
                    if (!v6RouteAdded && isTunnel && ifSpoof != null) {
                        RouteInfo fakeRoute = buildSpoofRoute(
                                routeCtor3, routeCtor4, ipPfxCtor,
                                laCtor2, laCtor4, ifSpoof, effIface);
                        if (fakeRoute != null) {
                            keep.add(fakeRoute);
                        }
                    }
                    param.setResult(keep);
                }
            };
            XposedBridge.hookAllMethods(lpClass, "getRoutes", routesHook);
            XposedBridge.hookAllMethods(lpClass, "getAllRoutes", routesHook);

            // 6. LinkProperties.toString built directly from hooked public getters
            XposedBridge.hookAllMethods(lpClass, "toString",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof LinkProperties)) return;
                            try {
                                LinkProperties lp = (LinkProperties) param.thisObject;
                                param.setResult(formatLinkProperties(lp));
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook linkproperties: " + t);
        }
    }

    private static String formatLinkProperties(LinkProperties lp) {
        StringBuilder sb = new StringBuilder("{");
        String iface = lp.getInterfaceName();
        if (iface != null) {
            sb.append("InterfaceName: ").append(iface).append(' ');
        }
        sb.append("LinkAddresses: [");
        List<LinkAddress> addrs = lp.getLinkAddresses();
        if (addrs != null && !addrs.isEmpty()) {
            sb.append(TextUtils.join(",", addrs));
        }
        sb.append("] DnsAddresses: [");
        List<InetAddress> dns = lp.getDnsServers();
        if (dns != null && !dns.isEmpty()) {
            sb.append(TextUtils.join(",", dns));
        }
        sb.append("] Domains: ").append(lp.getDomains());
        sb.append(" MTU: ").append(lp.getMtu());
        if (lp.isPrivateDnsActive()) {
            sb.append(" UsePrivateDns: true");
        }
        String privDns = lp.getPrivateDnsServerName();
        if (privDns != null) {
            sb.append(" PrivateDnsServerName: ").append(privDns);
        }
        sb.append(" Routes: [");
        List<RouteInfo> routes = lp.getRoutes();
        if (routes != null && !routes.isEmpty()) {
            sb.append(TextUtils.join(",", routes));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String readRawIface(Object lpObj, Field lpIfaceField) {
        if (lpObj == null || lpIfaceField == null) return null;
        try {
            Object v = lpIfaceField.get(lpObj);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static LinkAddress buildLinkAddress(Constructor<?> laCtor2, Constructor<?> laCtor4,
                                                InetAddress addr, int prefixLen) {
        if (addr == null) return null;
        try {
            if (laCtor2 != null) {
                return (LinkAddress) laCtor2.newInstance(addr, prefixLen);
            }
            if (laCtor4 != null) {
                return (LinkAddress) laCtor4.newInstance(addr, prefixLen, 0x80, 0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static RouteInfo buildSpoofRoute(Constructor<?> routeCtor3,
                                             Constructor<?> routeCtor4,
                                             Constructor<?> ipPfxCtor,
                                             Constructor<?> laCtor2,
                                             Constructor<?> laCtor4,
                                             InetAddress spoofAddr, String ifaceName) {
        try {
            byte[] masked = spoofAddr.getAddress().clone();
            for (int i = 8; i < 16; i++) masked[i] = 0;
            InetAddress netBase = InetAddress.getByAddress(masked);
            if (routeCtor4 != null && ipPfxCtor != null) {
                Object pfx = ipPfxCtor.newInstance(netBase, 64);
                return (RouteInfo) routeCtor4.newInstance(
                        pfx, null, ifaceName, RouteInfo.RTN_UNICAST);
            }
            if (routeCtor3 != null) {
                LinkAddress prefixLa = buildLinkAddress(laCtor2, laCtor4, netBase, 64);
                if (prefixLa != null) {
                    return (RouteInfo) routeCtor3.newInstance(prefixLa, null, ifaceName);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static InetAddress publicFallbackDnsV4() {
        try {
            return InetAddress.getByAddress(PUBLIC_FALLBACK_DNS_V4);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RouteInfo buildLanV4Route(Constructor<?> routeCtor3,
                                             Constructor<?> routeCtor4,
                                             Constructor<?> ipPfxCtor,
                                             Constructor<?> laCtor2,
                                             Constructor<?> laCtor4,
                                             String ifaceName) {
        try {
            InetAddress lan = fallbackLanV4Address();
            if (!(lan instanceof Inet4Address)) return null;
            int pfxLen = fallbackLanV4PrefixLen();
            byte[] raw = lan.getAddress();
            if (raw == null || raw.length != 4) return null;
            int mask = pfxLen >= 32 ? 0xFFFFFFFF : (pfxLen <= 0 ? 0 : (0xFFFFFFFF << (32 - pfxLen)));
            byte[] masked = new byte[]{
                    (byte) ((raw[0] & 0xFF) & ((mask >>> 24) & 0xFF)),
                    (byte) ((raw[1] & 0xFF) & ((mask >>> 16) & 0xFF)),
                    (byte) ((raw[2] & 0xFF) & ((mask >>> 8) & 0xFF)),
                    (byte) ((raw[3] & 0xFF) & (mask & 0xFF))
            };
            InetAddress netBase = InetAddress.getByAddress(masked);
            if (routeCtor4 != null && ipPfxCtor != null) {
                Object pfx = ipPfxCtor.newInstance(netBase, pfxLen);
                return (RouteInfo) routeCtor4.newInstance(
                        pfx, null, ifaceName, RouteInfo.RTN_UNICAST);
            }
            if (routeCtor3 != null) {
                LinkAddress prefixLa = buildLinkAddress(laCtor2, laCtor4, netBase, pfxLen);
                if (prefixLa != null) {
                    return (RouteInfo) routeCtor3.newInstance(prefixLa, null, ifaceName);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Looks up a real physical IPv4 (preferring wlan0, then any active non-tunnel Wi-Fi/Ethernet
     * interface, then cellular), or synthesizes a deterministic RFC1918 LAN IPv4.
     */
    private static InetAddress fallbackLanV4Address() {
        InetAddress direct = firstRoutableV4On("wlan0");
        if (direct != null) return direct;
        InetAddress cellCandidate = null;
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp() || isHiddenInterface(ni)) {
                    continue;
                }
                InetAddress addr = firstRoutableV4(ni);
                if (addr == null) continue;
                if (!isCellularName(ni.getName())) {
                    return addr;
                }
                if (cellCandidate == null) {
                    cellCandidate = addr;
                }
            }
        } catch (Throwable ignored) {}
        if (cellCandidate != null) return cellCandidate;
        try {
            int host = 100 + (int) Math.floorMod(cfgSeed, 120L);
            return InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, (byte) host});
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static InetAddress firstRoutableV4On(String ifaceName) {
        try {
            return firstRoutableV4(NetworkInterface.getByName(ifaceName));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static InetAddress firstRoutableV4(NetworkInterface ni) {
        if (ni == null) return null;
        try {
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs != null && addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()
                        && !a.isLinkLocalAddress() && !is464Xlat(a)
                        && !isBenchmarkFakeIp(a)) {
                    return a;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Reads the live IPv4 prefix length on the active physical interface, defaulting to 24. */
    private static int fallbackLanV4PrefixLen() {
        int direct = firstRoutableV4PrefixLenOn("wlan0");
        if (direct > 0) return direct;
        int cellPfx = -1;
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp() || isHiddenInterface(ni)) {
                    continue;
                }
                int len = firstRoutableV4PrefixLen(ni);
                if (len <= 0) continue;
                if (!isCellularName(ni.getName())) {
                    return len;
                }
                if (cellPfx < 0) {
                    cellPfx = len;
                }
            }
        } catch (Throwable ignored) {}
        return cellPfx > 0 ? cellPfx : 24;
    }

    private static int firstRoutableV4PrefixLenOn(String ifaceName) {
        try {
            return firstRoutableV4PrefixLen(NetworkInterface.getByName(ifaceName));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int firstRoutableV4PrefixLen(NetworkInterface ni) {
        if (ni == null) return -1;
        try {
            List<InterfaceAddress> list = ni.getInterfaceAddresses();
            if (list != null) {
                for (InterfaceAddress ia : list) {
                    if (ia == null) continue;
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress() && !is464Xlat(a)
                            && !isBenchmarkFakeIp(a)) {
                        int len = ia.getNetworkPrefixLength();
                        if (len >= 8 && len <= 30) {
                            return len;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static LinkAddress fallbackLanV4LinkAddress(Constructor<?> laCtor2,
                                                        Constructor<?> laCtor4) {
        InetAddress v4 = fallbackLanV4Address();
        return v4 != null ? buildLinkAddress(laCtor2, laCtor4, v4, fallbackLanV4PrefixLen()) : null;
    }

    private static boolean isHiddenInterface(NetworkInterface ni) {
        if (ni == null) return false;
        String name = ni.getName();
        if (badName(name)) return true;
        if (name == null || "lo".equals(name) || isCellularName(name)) return false;
        try {
            return ni.isPointToPoint();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean badName(String name) {
        if (name == null || name.isEmpty()) return false;
        String low = name.trim().toLowerCase(Locale.ROOT);
        if (cfgHiddenIfaces.contains(low)) {
            return true;
        }
        return com.walter.claudewilson.model.NetworkRules.isKnownTunnelPrefix(low);
    }

    private static boolean isCellularName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.matches("^(r_?rmnet|rev_rmnet|cdma_rmnet|rmnet|ccmni|pdp|umts|svnet|seth_|wwan|ril|clat|epdg|v4-rmnet|v4-ccmni|v4-pdp).*");
    }

    /** True for 2000::/3 global unicast only. */
    private static boolean isGlobalUnicast(InetAddress addr) {
        byte[] raw = addr.getAddress();
        return raw != null && raw.length == 16 && (raw[0] & 0xE0) == 0x20;
    }

    /** True for fc00::/7 Unique Local Addresses (often assigned on TUN devices). */
    private static boolean isUlaV6(InetAddress addr) {
        byte[] raw = addr.getAddress();
        return raw != null && raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
    }

    /** True for RFC 2544 198.18.0.0/15 benchmark space used by TUN fake-ip stacks. */
    private static boolean isBenchmarkFakeIp(InetAddress addr) {
        byte[] raw = addr.getAddress();
        return raw != null && raw.length == 4
                && (raw[0] & 0xFF) == 198 && (raw[1] & 0xFE) == 18;
    }

    /** 464XLAT CLAT addresses live in 192.0.0.0/29 — not a standalone user-facing IPv4. */
    private static boolean is464Xlat(InetAddress addr) {
        byte[] raw = addr.getAddress();
        return raw != null && raw.length == 4
                && (raw[0] & 0xFF) == 192 && raw[1] == 0 && raw[2] == 0
                && (raw[3] & 0xFF) <= 7;
    }

    // ---- IPv6 address generation ----

    private static byte[] generateV6Bytes(String cidr, long seed, String ifaceName) {
        if (cidr == null) return null;
        int slash = cidr.indexOf('/');
        if (slash < 0) return null;
        String base = cidr.substring(0, slash);
        int prefixLen;
        try {
            prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefixLen <= 0 || prefixLen > 120) return null;

        byte[] addr = parseV6(base);
        if (addr == null) return null;

        byte[] hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(cidr.getBytes("UTF-8"));
            md.update(longBytes(seed));
            md.update(cachedBootId != null ? cachedBootId : new byte[0]);
            if (ifaceName != null) {
                md.update(ifaceName.getBytes("UTF-8"));
            }
            hash = md.digest();
        } catch (Exception e) {
            return null;
        }

        int fullBytes = prefixLen / 8;
        int remainBits = prefixLen % 8;
        int hi = 0;
        if (remainBits != 0 && fullBytes < 16) {
            int mask = (0xFF << (8 - remainBits)) & 0xFF;
            addr[fullBytes] = (byte) ((addr[fullBytes] & mask) | (hash[hi++] & ~mask));
            fullBytes++;
        }
        for (int i = fullBytes; i < 16 && hi < hash.length; i++) {
            addr[i] = hash[hi++];
        }
        if (addr[15] == 0) {
            addr[15] = 1;
        }
        return addr;
    }

    private static byte[] parseV6(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;

        int dc = text.indexOf("::");
        if (dc >= 0 && dc != text.lastIndexOf("::")) return null;

        String[] parts;
        if (dc >= 0) {
            String leftPart = text.substring(0, dc);
            String rightPart = text.substring(dc + 2);
            String[] lp = leftPart.isEmpty() ? new String[0] : leftPart.split(":", -1);
            String[] rp = rightPart.isEmpty() ? new String[0] : rightPart.split(":", -1);
            int missing = 8 - lp.length - rp.length;
            if (missing < 1) return null;
            parts = new String[8];
            int idx = 0;
            for (String s : lp) parts[idx++] = s;
            for (int i = 0; i < missing; i++) parts[idx++] = "0";
            for (String s : rp) parts[idx++] = s;
        } else {
            parts = text.split(":", -1);
            if (parts.length != 8) return null;
        }

        byte[] addr = new byte[16];
        for (int i = 0; i < 8; i++) {
            String g = parts[i];
            if (g == null || g.isEmpty() || g.length() > 4) return null;
            int val = 0;
            for (int k = 0; k < g.length(); k++) {
                int d = Character.digit(g.charAt(k), 16);
                if (d < 0) return null;
                val = (val << 4) | d;
            }
            addr[i * 2] = (byte) (val >> 8);
            addr[i * 2 + 1] = (byte) val;
        }
        return addr;
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) v; v >>= 8; }
        return b;
    }
}

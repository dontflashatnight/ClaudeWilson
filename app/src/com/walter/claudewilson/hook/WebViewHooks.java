package com.walter.claudewilson.hook;

import android.webkit.GeolocationPermissions;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebView hooks:
 * <ol>
 *   <li>Auto-grants the geolocation prompt (only when location is configured).</li>
 *   <li>Injects a JS shim that wraps {@code RTCPeerConnection} to rewrite or drop global IPv6
 *       in ICE candidates and SDP lines without touching DTLS fingerprints or IPv4 candidates.</li>
 * </ol>
 */
final class WebViewHooks {
    static volatile String status = "not-run";

    private static final Set<Class<?>> hookedClientClasses =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private static final Set<Class<?>> hookedSettingsClasses =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private WebViewHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        hookedClientClasses.clear();
        hookedSettingsClasses.clear();
        boolean geoHooked = false;
        boolean rtcInjected = false;

        if (config.hasLocation()) {
            try {
                Class<?> chrome = XposedHelpers.findClass(
                        "android.webkit.WebChromeClient", loader);
                XposedBridge.hookAllMethods(chrome, "onGeolocationPermissionsShowPrompt",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (param.args.length < 2
                                        || !(param.args[1] instanceof GeolocationPermissions.Callback)) {
                                    return;
                                }
                                String origin = param.args[0] == null
                                        ? "" : String.valueOf(param.args[0]);
                                try {
                                    ((GeolocationPermissions.Callback) param.args[1])
                                            .invoke(origin, true, false);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                geoHooked = true;
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook webview geolocation: " + t);
            }
        }

        InetAddress spoof = NetworkHooks.spoofForDefaultInterface();
        String spoofedV6 = spoof != null ? spoof.getHostAddress() : null;
        boolean needsRtc = config.ipv6Disabled
                || spoofedV6 != null
                || !config.ipv6Prefix.isEmpty()
                || !config.ipv6MobilePrefix.isEmpty();

        // Rewrite WebView User-Agent model/release when persona overrides Build.MODEL / RELEASE.
        final String uaRel = config.build != null ? config.build.get("VERSION.RELEASE") : null;
        final String uaModel = config.build != null ? config.build.get("MODEL") : null;
        boolean uaHooked = false;
        if ((uaRel != null && !uaRel.isEmpty()) || (uaModel != null && !uaModel.isEmpty())) {
            try {
                Class<?> ws = XposedHelpers.findClass("android.webkit.WebSettings", loader);
                final XC_MethodHook uaHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (res instanceof String) {
                            String rewritten = com.walter.claudewilson.model.BodyguardEventCodec
                                    .rewriteUserAgent((String) res, uaRel, uaModel);
                            if (rewritten != null && !rewritten.equals(res)) {
                                param.setResult(rewritten);
                            }
                        }
                    }
                };
                XposedBridge.hookAllMethods(ws, "getDefaultUserAgent", uaHook);
                // WebSettings.getUserAgentString() is abstract; hook concrete subclasses via getSettings().
                Class<?> wvCls = XposedHelpers.findClass("android.webkit.WebView", loader);
                XposedBridge.hookAllMethods(wvCls, "getSettings", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object settings = param.getResult();
                        if (settings == null) return;
                        Class<?> concrete = settings.getClass();
                        if (hookedSettingsClasses.add(concrete)) {
                            try {
                                XposedBridge.hookAllMethods(concrete, "getUserAgentString", uaHook);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                uaHooked = true;
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook webview ua: " + t);
            }
        }

        boolean needsEnv = (config.locale != null && !config.locale.isEmpty())
                || (config.tz != null && !config.tz.isEmpty());
        if (needsRtc || needsEnv) {
            final String js = buildCombinedShim(needsRtc, spoofedV6, config);
            try {
                final XC_MethodHook clientHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length < 1
                                || !(param.args[0] instanceof WebView)) return;
                        evalSafe((WebView) param.args[0], js);
                    }
                };
                Class<?> wvc = XposedHelpers.findClass("android.webkit.WebViewClient", loader);
                hookClientClass(wvc, clientHook);

                Class<?> wvClass = XposedHelpers.findClass("android.webkit.WebView", loader);
                XposedBridge.hookAllMethods(wvClass, "setWebViewClient", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof WebViewClient) {
                            hookClientClass(param.args[0].getClass(), clientHook);
                        }
                    }
                });

                XC_MethodHook navHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof WebView) {
                            evalSafe((WebView) param.thisObject, js);
                        }
                    }
                };
                XposedBridge.hookAllMethods(wvClass, "loadUrl", navHook);
                XposedBridge.hookAllMethods(wvClass, "loadDataWithBaseURL", navHook);

                rtcInjected = true;
                if (HookEntry.debugLog) {
                    XposedBridge.log("ClaudeWilsonHook webview shim armed"
                            + (spoofedV6 != null ? ", spoofed=" + spoofedV6 : ", drop-mode"));
                }
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook webrtc shim: " + t);
            }
        }

        status = (geoHooked ? "ok" : "off")
                + (rtcInjected ? "+rtc" : "")
                + (uaHooked ? "+ua" : "");
        if (HookEntry.debugLog) {
            XposedBridge.log("ClaudeWilsonHook webview status=" + status);
        }
    }

    private static void hookClientClass(Class<?> cls, XC_MethodHook hook) {
        if (cls == null || !hookedClientClasses.add(cls)) return;
        try {
            XposedBridge.hookAllMethods(cls, "onPageStarted", hook);
            XposedBridge.hookAllMethods(cls, "onPageFinished", hook);
        } catch (Throwable ignored) {}
    }

    private static void evalSafe(WebView wv, String js) {
        try {
            wv.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    private static String buildCombinedShim(boolean includeRtc, String spoofedV6,
                                            HookConfig config) {
        StringBuilder sb = new StringBuilder(2048);
        if (includeRtc) {
            sb.append(buildRtcShim(spoofedV6));
        }
        String envJs = buildEnvShim(config);
        if (!envJs.isEmpty()) {
            sb.append(envJs);
        }
        return sb.toString();
    }

    /** Aligns JS navigator.language(s), Intl timezone, and Date.getTimezoneOffset with persona. */
    private static String buildEnvShim(HookConfig config) {
        String loc = config.locale == null ? "" : config.locale.trim();
        String tz = config.tz == null ? "" : config.tz.trim();
        if (loc.isEmpty() && tz.isEmpty()) {
            return "";
        }
        StringBuilder js = new StringBuilder(768);
        js.append("(function(){if(window.__cw_env)return;window.__cw_env=1;");
        if (!loc.isEmpty() && loc.matches("[A-Za-z0-9_-]{2,24}")) {
            String langTag = loc.replace('_', '-');
            StringBuilder langs = new StringBuilder("['").append(langTag).append("'");
            if (config.localeList != null) {
                for (String item : config.localeList) {
                    String t = item == null ? "" : item.trim().replace('_', '-');
                    if (!t.isEmpty() && !t.equals(langTag) && t.matches("[A-Za-z0-9-]{2,24}")) {
                        langs.append(",'").append(t).append("'");
                    }
                }
            }
            int dash = langTag.indexOf('-');
            if (dash > 0) {
                String base = langTag.substring(0, dash);
                if (langs.indexOf("'" + base + "'") < 0) {
                    langs.append(",'").append(base).append("'");
                }
            }
            langs.append("]");
            js.append("try{Object.defineProperty(Navigator.prototype,'language',{get:function(){return '")
                    .append(langTag).append("';},configurable:true});")
                    .append("Object.defineProperty(Navigator.prototype,'languages',{get:function(){return ")
                    .append(langs).append(";},configurable:true});}catch(e){}");
        }
        if (!tz.isEmpty() && tz.matches("[A-Za-z0-9/_+-]{2,48}")) {
            int offsetMin = -(java.util.TimeZone.getTimeZone(tz)
                    .getOffset(System.currentTimeMillis()) / 60000);
            js.append("try{var oRO=Intl.DateTimeFormat.prototype.resolvedOptions;")
                    .append("Intl.DateTimeFormat.prototype.resolvedOptions=function(){")
                    .append("var r=oRO.call(this);r.timeZone='").append(tz).append("';return r;};")
                    .append("Date.prototype.getTimezoneOffset=function(){return ")
                    .append(offsetMin).append(";};}catch(e){}");
        }
        js.append("})();");
        return js.toString();
    }

    /**
     * Builds a JS snippet that wraps {@code RTCPeerConnection} to rewrite or drop global-unicast
     * IPv6 addresses in ICE candidates and SDP candidate/connection lines, leaving DTLS
     * {@code a=fingerprint:sha-256}, link-local IPv6, and IPv4 candidates untouched.
     */
    private static String buildRtcShim(String spoofedV6) {
        String sVal = spoofedV6 != null ? spoofedV6 : "";
        return "(function(){"
                + "if(window.__cw_rtc)return;window.__cw_rtc=1;"
                + "var S='" + sVal + "';"
                + "if(typeof RTCPeerConnection==='undefined')return;"
                + "function isG6(t){"
                + "if(!t||t.indexOf(':')<0)return false;"
                + "if(!/^[23][0-9a-f]{0,3}(?::[0-9a-f]{0,4}){2,7}$/i.test(t))return false;"
                + "return t.indexOf('::')>=0||t.split(':').length===8;"
                + "}"
                + "function fixLine(ln){"
                + "var p=ln.trim().split(/\\s+/);"
                + "if(p.length<5)return ln;"
                + "if(isG6(p[4])){if(!S)return null;p[4]=S;}"
                + "for(var i=5;i<p.length-1;i++){"
                + "if(p[i]==='raddr'&&isG6(p[i+1])){p[i+1]=S||'0.0.0.0';}"
                + "}"
                + "var lead=ln.match(/^\\s*/)[0];"
                + "return lead+p.join(' ');"
                + "}"
                + "function fix(c){"
                + "if(!c||!c.candidate)return c;"
                + "var n=fixLine(c.candidate);"
                + "if(n===null)return null;"
                + "if(n!==c.candidate){try{return new RTCIceCandidate({candidate:n,"
                + "sdpMid:c.sdpMid,sdpMLineIndex:c.sdpMLineIndex,"
                + "usernameFragment:c.usernameFragment});}catch(e){}}"
                + "return c;}"
                + "function fixSdp(d){"
                + "if(!d||!d.sdp)return d;"
                + "var lines=d.sdp.split(/\\r?\\n/),out=[],ch=false;"
                + "for(var i=0;i<lines.length;i++){"
                + "var ln=lines[i];"
                + "if(ln.indexOf('a=candidate:')===0){"
                + "var fl=fixLine(ln);"
                + "if(fl===null){ch=true;continue;}"
                + "if(fl!==ln)ch=true;"
                + "out.push(fl);"
                + "}else if(ln.indexOf('c=IN IP6 ')===0){"
                + "var ca=ln.substring(9).trim();"
                + "if(isG6(ca)){out.push(S?'c=IN IP6 '+S:'c=IN IP4 0.0.0.0');ch=true;}else out.push(ln);"
                + "}else if(ln.indexOf('a=rtcp:')===0&&ln.indexOf(' IN IP6 ')>0){"
                + "var rp=ln.trim().split(/\\s+/);"
                + "if(rp.length>=4&&isG6(rp[3])){if(S){rp[3]=S;}else{rp[2]='IP4';rp[3]='0.0.0.0';}out.push(rp.join(' '));ch=true;}else out.push(ln);"
                + "}else{out.push(ln);}"
                + "}"
                + "if(!ch)return d;"
                + "return new RTCSessionDescription({type:d.type,sdp:out.join('\\r\\n')});"
                + "}"
                + "var O=RTCPeerConnection.prototype;"
                + "var oAE=O.addEventListener;"
                + "function wrapIce(orig){return function(ev){"
                + "if(ev&&ev.candidate){var fc=fix(ev.candidate);"
                + "if(fc===null)return;"
                + "if(fc!==ev.candidate){var ne=new Event('icecandidate');"
                + "ne.candidate=fc;return orig.call(this,ne);}}"
                + "return orig.call(this,ev);};}"
                + "O.addEventListener=function(t,fn,o){"
                + "if(t==='icecandidate'&&typeof fn==='function')fn=wrapIce(fn);"
                + "return oAE.call(this,t,fn,o);};"
                + "var oic=Object.getOwnPropertyDescriptor(O,'onicecandidate');"
                + "if(oic&&oic.set){Object.defineProperty(O,'onicecandidate',{"
                + "set:function(fn){if(typeof fn==='function')fn=wrapIce(fn);"
                + "oic.set.call(this,fn);},get:oic.get,configurable:true});}"
                + "var oLD=Object.getOwnPropertyDescriptor(O,'localDescription');"
                + "if(oLD&&oLD.get){Object.defineProperty(O,'localDescription',{"
                + "get:function(){return fixSdp(oLD.get.call(this));},configurable:true});}"
                + "function wrapDesc(orig){return function(){"
                + "var a=Array.prototype.slice.call(arguments);"
                + "if(typeof a[0]==='function'){var cb=a[0];a[0]=function(d){return cb(fixSdp(d));};"
                + "return orig.apply(this,a);}"
                + "return orig.apply(this,a).then(fixSdp);};}"
                + "if(O.createOffer)O.createOffer=wrapDesc(O.createOffer);"
                + "if(O.createAnswer)O.createAnswer=wrapDesc(O.createAnswer);"
                + "})();";
    }
}

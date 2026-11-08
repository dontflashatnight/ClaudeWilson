import Java from "frida-java-bridge";

/* EnvLens root-mode persona payload — injected into the TARGET process.
 * Config: /data/local/tmp/persona.json (EnvLens writes, mode 644).
 * One seed -> every collector sees the SAME values.
 * v2: close side-channel leaks — SubscriptionInfo objects carry their own
 * mcc/mnc/name; getAllCellInfo leaks serving-cell mcc; LinkProperties leaks
 * tun names; Locale/TimeZone coherence; installer source; accounts.
 */

const DEF = {
  // Public catalogue sample, not a capture of any one device.
  simOperator: "52501", simCountryIso: "sg", simOperatorName: "SingTel",
  netOperator: "52501", netOperatorName: "SingTel", netCountryIso: "sg",
  simCarrierId: -1,
  androidId: "0123456789abcdef",
  build: {
    MANUFACTURER: "samsung", MODEL: "SM-S928B", BRAND: "samsung",
    DEVICE: "e3q", PRODUCT: "e3qxeea", HARDWARE: "qcom", BOARD: "kalama",
    FINGERPRINT: "samsung/e3qxeea/e3q:15/AP3A.240905.015.A2/S928BXXS5AYD4:user/release-keys",
    TAGS: "release-keys", TYPE: "user", ID: "AP3A.240905.015.A2",
    DISPLAY: "AP3A.240905.015.A2.S928BXXS5AYD4",
    "VERSION.RELEASE": "15", "VERSION.SDK_INT": 35,
    "VERSION.SECURITY_PATCH": "2024-11-01",
    "VERSION.INCREMENTAL": "S928BXXS5AYD4",
  },
  props: { "ro.debuggable": "0", "ro.secure": "1", "ro.build.tags": "release-keys" },
  // optional: locale:"en-SG", tz:"Asia/Singapore", hideAccounts:true
};

let P = DEF;
try {
  var Files = Java.use("java.nio.file.Files");
  var Paths = Java.use("java.nio.file.Paths");
  var Str = Java.use("java.lang.String");
  var b = Files.readAllBytes(Paths.get("/data/local/tmp/persona.json", []));
  var cfg = JSON.parse(Str.$new(b, "UTF-8"));
  P = Object.assign({}, DEF, cfg, { build: Object.assign({}, DEF.build, cfg.build || {}),
                                  props: Object.assign({}, DEF.props, cfg.props || {}) });
} catch (e) {}

const MCC = parseInt(P.simOperator.substring(0, 3));
const MNC = parseInt(P.simOperator.substring(3));
const IF_BAD = /^(tun|ppp|wg|tap|utun)/;

/* ================= cloaking layer =================
 * Cover every read path that could see injection residue. Layers:
 *   A. /proc/self + pid maps|smaps|status -> filtered copies (Java+libc open)
 *   B. Runtime.exec(cat maps / getprop) -> argv rewrite to sanitized files
 *   C. native __system_property_get -> persona props (deeper than Java SP.get)
 * Residuals (documented, user-mode can't reach): inline svc syscalls,
 * dl_iterate_phdr/solist enumeration, frida thread comm names,
 * native posix_spawn exec.
 */
const SELF_PID = Process.id;
const PROC_RX = new RegExp("^/proc/(self|" + SELF_PID + "|" +
    SELF_PID + "/task/[0-9]+)/(maps|smaps|status|stat)$");
const AGENT_RX = /frida|gadget|finject|linjector|gum-js|libbrid|re\.frida|gmain|gdbus|pool-spawner/i;
var CLOAK_DIR = "/data/local/tmp/.el-cloak";
var MAPS_FAKE = null, STATUS_FAKE = null, STAT_FAKE = null;
var PROPS = {};

/* build -> ro.* prop map (persona overrides + explicit P.props) */
function propMap() {
  var b = P.build || {}, m = {};
  if (b.MODEL) m["ro.product.model"] = b.MODEL;
  if (b.BRAND) m["ro.product.brand"] = b.BRAND;
  if (b.PRODUCT) m["ro.product.name"] = b.PRODUCT;
  if (b.DEVICE) m["ro.product.device"] = b.DEVICE;
  if (b.MANUFACTURER) m["ro.product.manufacturer"] = b.MANUFACTURER;
  if (b.BOARD) m["ro.product.board"] = b.BOARD;
  if (b.HARDWARE) m["ro.hardware"] = b.HARDWARE;
  if (b.FINGERPRINT) m["ro.build.fingerprint"] = b.FINGERPRINT;
  if (b.DISPLAY) m["ro.build.display.id"] = b.DISPLAY;
  if (b.ID) m["ro.build.id"] = b.ID;
  if (b.TAGS) m["ro.build.tags"] = b.TAGS;
  if (b.TYPE) m["ro.build.type"] = b.TYPE;
  if (b["VERSION.RELEASE"]) m["ro.build.version.release"] = b["VERSION.RELEASE"];
  if (b["VERSION.SDK_INT"]) m["ro.build.version.sdk"] = "" + b["VERSION.SDK_INT"];
  if (b["VERSION.SECURITY_PATCH"]) m["ro.build.version.security_patch"] = b["VERSION.SECURITY_PATCH"];
  if (b["VERSION.INCREMENTAL"]) m["ro.build.version.incremental"] = b["VERSION.INCREMENTAL"];
  for (var k in (P.props || {})) m[k] = P.props[k];
  return m;
}

/* sanitize /proc text: drop agent lines, RWX anon (JIT signature),
 * TracerPid>0 in status/stat. */
function sanitizeProc(name, text) {
  var out = [];
  var lines = text.split("\n");
  for (var i = 0; i < lines.length; i++) {
    var ln = lines[i];
    if (!ln) continue;
    if (name === "status" || name === "stat") {
      if (/^TracerPid:\s*\d/.test(ln)) { out.push("TracerPid:\t0"); continue; }
      out.push(ln); continue;
    }
    if (AGENT_RX.test(ln)) continue;
    // anonymous rwxp — frida/gum codegen signature (legit JIT is rw-p/r-xp)
    if (/ rwxp /.test(ln) && !/\S+\s*$/.test(ln.split(" rwxp ")[1].trim()))
      continue;
    if (/\s(rwxp)\s/.test(ln) && ln.indexOf("/") === -1) continue;
    out.push(ln);
  }
  return out.join("\n") + "\n";
}

function writeFake(dir, name, body) {
  var p = dir + "/" + name;
  try {
    var f = Java.use("java.io.FileOutputStream").$new(p);
    f.write(Java.use("java.lang.String").$new(body).getBytes());
    f.close();
    return p;
  } catch (e) { return null; }
}

function readAll(pth) {
  try {
    var Files = Java.use("java.nio.file.Files");
    var Paths = Java.use("java.nio.file.Paths");
    var Str = Java.use("java.lang.String");
    return Str.$new(Files.readAllBytes(Paths.get(pth, [])), "UTF-8").toString();
  } catch (e) { return null; }
}

/* per-open fd redirect for the libc layer: fake files were pre-written to
 * CLOAK_DIR; native open/openat/fopen of a real proc path gets its argv
 * pointer swapped to the fake — kernel returns a fd on our copy. */
function cloakPath(p) {
  if (!p || !PROC_RX.test(p)) return null;
  var base = p.substring(p.lastIndexOf("/") + 1);
  if (base === "maps" || base === "smaps") return MAPS_FAKE;
  if (base === "status") return STATUS_FAKE;
  if (base === "stat") return STAT_FAKE;
  return MAPS_FAKE;
}

/* hook every overload of klass.name to return strVal or intVal
 * depending on declared return type (handles String/int variants).
 * returnType may be JNI-style ("I") — match primitives broadly;
 * string values are pre-marshaled as java String so CharSequence
 * returns never collapse to null. */
function retAll(klass, name, strVal, intVal) {
  try {
    Java.use(klass)[name].overloads.forEach(function (o) {
      var isNum = /^(int|long|short|byte|I|J|S|B|Z)$/.test(o.returnType);
      var sv = strVal === null ? null
          : Java.use("java.lang.String").$new(String(strVal));
      o.implementation = function () { return isNum ? intVal : sv; };
    });
  } catch (e) {}
}

/* ================= native cloak (libc) =================
 * Interceptor on the open-family: swap the path arg pointer to our filtered
 * copy — kernel hands back a fd on the fake, caller's read path is untouched.
 * __system_property_get -> persona props (native collectors bypass Java SP).
 * Residual: inline svc, solist enumeration, native posix_spawn. */
var KEEP = [];
function exp(name) {
  try {                              // frida 17: module-scoped API
    var m = Process.getModuleByName("libc.so");
    if (m.findExportByName) return m.findExportByName(name);
    if (m.getExportByName) return m.getExportByName(name);
  } catch (e) {}
  try { return Module.getGlobalExportByName(name); } catch (e) {}
  try { return Module.getExportByName("libc.so", name); } catch (e) {}
  return null;
}
(function nativeCloak() {
  var targets = [
    ["open", 0], ["openat", 1], ["openat64", 1], ["__open", 0],
    ["__open_2", 0], ["__openat", 1], ["__openat_2", 1],
    ["fopen", 0], ["fopen64", 0],
  ];
  targets.forEach(function (t) {
    var sym = exp(t[0]);
    if (sym === null) return;
    try {
      Interceptor.attach(sym, {
        onEnter: function (args) {
          try {
            var p = args[t[1]].readUtf8String();
            var fake = cloakPath(p);
            if (fake) {
              var fp = Memory.allocUtf8String(fake);
              KEEP.push(fp);
              args[t[1]] = fp;
            }
          } catch (e) {}
        },
      });
    } catch (e) {}
  });

  // __system_property_get(name, value) -> int len
  try {
    var spg = exp("__system_property_get");
    if (spg === null) return;
    Interceptor.attach(spg, {
      onEnter: function (args) {
        try { this.pname = args[0].readUtf8String(); }
        catch (e) { this.pname = null; }
        this.vp = args[1];
      },
      onLeave: function (rv) {
        try {
          if (this.pname && this.pname in PROPS) {
            var v = String(PROPS[this.pname]);
            this.vp.writeUtf8String(v);
            rv.replace(v.length);
          }
        } catch (e) {}
      },
    });
  } catch (e) {}
})();

Java.perform(function () {

  // ==================== cloak prep (before any hooks) ====================
  try {
    var ctx0 = null;
    try { ctx0 = Java.use("android.app.ActivityThread").currentApplication(); }
    catch (e) {}
    if (ctx0 !== null) {
      try { CLOAK_DIR = ctx0.getCacheDir().getPath() + "/.el"; }
      catch (e) {}
    }
    Java.use("java.io.File").$new(CLOAK_DIR).mkdirs();
    PROPS = propMap();

    // real proc snapshots -> filtered copies
    MAPS_FAKE   = writeFake(CLOAK_DIR, "maps",
        sanitizeProc("maps", readAll("/proc/self/maps") || ""));
    STATUS_FAKE = writeFake(CLOAK_DIR, "status",
        sanitizeProc("status", readAll("/proc/self/status") || ""));
    STAT_FAKE   = writeFake(CLOAK_DIR, "stat",
        sanitizeProc("stat", readAll("/proc/self/stat") || ""));

    // getprop dump (ProcessBuilder runs BEFORE exec hook is armed)
    try {
      var pb0 = Java.use("java.lang.ProcessBuilder").$new(
          Java.array("java.lang.String", ["getprop"]));
      var pr0 = pb0.start();
      var br0 = Java.use("java.io.BufferedReader").$new(
          Java.use("java.io.InputStreamReader").$new(pr0.getInputStream()));
      var ln0, dump = [];
      while ((ln0 = br0.readLine()) !== null) dump.push(String(ln0));
      var ov = dump.map(function (l) {
        var m = l.match(/^\[(.+)\]:\s*\[(.*)\]$/);
        return (m && m[1] in PROPS) ? "[" + m[1] + "]: [" + PROPS[m[1]] + "]" : l;
      });
      writeFake(CLOAK_DIR, "propdump", ov.join("\n") + "\n");
      for (var k in PROPS) writeFake(CLOAK_DIR,
          "prop_" + k.replace(/[^A-Za-z0-9_.-]/g, "_"), PROPS[k] + "\n");
    } catch (e) {}

    // agent thread names: structural rename via task/comm write —
    // covers every reader (native scans, /proc walkers) with zero
    // filtering. Names borrowed from stock ART threads.
    var CLEAN_NAMES = ["FinalizerDaemon", "ReferenceQueueD",
        "HeapTaskDaemon", "Signal Catcher", "Jit thread pool",
        "queued-work-lo", "RenderThread", "binder:sync"];
    var cnIdx = 0;
    var renameThreads = function () {
      try {
        var td = Java.use("java.io.File").$new("/proc/self/task").listFiles();
        for (var i = 0; i < td.length; i++) {
          try {
            var cp = td[i].getPath() + "/comm";
            var nm = readAll(cp) || "";
            if (!AGENT_RX.test(nm)) continue;
            var f = Java.use("java.io.FileOutputStream").$new(cp);
            f.write(Java.use("java.lang.String").$new(
                CLEAN_NAMES[cnIdx++ % CLEAN_NAMES.length]).getBytes());
            f.close();
          } catch (e) {}
        }
      } catch (e) {}
    };
    renameThreads();
    setInterval(renameThreads, 3000);   // late-spawned agent threads
  } catch (e) {}

  // ---- cloak: Java file readers -> filtered copies ----
  // Android funnel #1: libcore.io.IoBridge.open(String,int) — every
  // java.io open (FileInputStream/RandomAccessFile/FileReader) lands here.
  try {
    var IB = Java.use("libcore.io.IoBridge");
    IB.open.overloads.forEach(function (o) {
      o.implementation = function (path, flags) {
        try {
          var fake = cloakPath(String(path));
          if (fake) return o.call(this, fake, flags);
        } catch (e) {}
        return o.call(this, path, flags);
      };
    });
  } catch (e) {}

  // Android funnel #2: sun.nio.fs.UnixNativeDispatcher — nio/Files paths.
  // TWO signatures coexist: open(String,…,…) AND open0(long pathAddr,…,…)
  // — the long is a native pointer to path bytes (that is the read open).
  var undPath = function (pa) {
    try {
      var s = String(pa);
      if (s.charAt(0) === "/") return s;               // java String
      return new NativePointer(s).readUtf8String();   // long pathAddr
    } catch (e) { return null; }
  };
  var libcOpenF = new NativeFunction(exp("open"),
      "int", ["pointer", "int", "int"]);
  ["open", "open0", "fopen", "fopen0"].forEach(function (m) {
    try {
      Java.use("sun.nio.fs.UnixNativeDispatcher")[m]
          .overloads.forEach(function (o) {
        o.implementation = function (pa, a1, a2) {
          try {
            var fake = cloakPath(undPath(pa));
            if (fake) {
              if (String(pa).charAt(0) === "/")
                return o.call(this, fake, a1, a2);          // String sig
              var fp = Memory.allocUtf8String(fake);
              return libcOpenF(fp, Number(a1) || 0, 0);     // ptr sig → fd
            }
          } catch (e) {}
          return o.call(this, pa, a1, a2);
        };
      });
    } catch (e) {}
  });
  ["openat", "openat0"].forEach(function (m) {
    try {
      Java.use("sun.nio.fs.UnixNativeDispatcher")[m]
          .overloads.forEach(function (o) {
        o.implementation = function (fd, pa, a2, a3) {
          try {
            var fake = cloakPath(undPath(pa));
            if (fake) {
              if (String(pa).charAt(0) === "/")
                return o.call(this, fd, fake, a2, a3);
              var fp = Memory.allocUtf8String(fake);
              return libcOpenF(fp, Number(a2) || 0, 0);
            }
          } catch (e) {}
          return o.call(this, fd, pa, a2, a3);
        };
      });
    } catch (e) {}
  });

  // ---- cloak: exec argv rewrite (cat maps / getprop) ----
  try {
    var PB = Java.use("java.lang.ProcessBuilder");
    PB.start.overloads.forEach(function (o) {
      o.implementation = function () {
        try {
          var cmd = this.command(), n = cmd.size();
          for (var i = 0; i < n; i++) {
            var rp = cloakPath(String(cmd.get(i)));
            if (rp) cmd.set(i, rp);
          }
          var a0 = n > 0 ? String(cmd.get(0)) : "";
          if (/(^|\/)getprop$/.test(a0)) {
            var key = n > 1 ? String(cmd.get(1)) : "";
            var tgt = key
                ? CLOAK_DIR + "/prop_" + key.replace(/[^A-Za-z0-9_.-]/g, "_")
                : CLOAK_DIR + "/propdump";
            if (Java.use("java.io.File").$new(tgt).exists()) {
              cmd.clear();
              cmd.add("/system/bin/cat"); cmd.add(tgt);
            }
          }
        } catch (e) {}
        return o.call(this);
      };
    });
  } catch (e) {}

  // ---- TelephonyManager getters ----
  var TM = Java.use("android.telephony.TelephonyManager");
  var ret = {
    getSimOperator: P.simOperator, getSimCountryIso: P.simCountryIso,
    getSimOperatorName: P.simOperatorName,
    getNetworkOperator: P.netOperator,
    getNetworkOperatorName: P.netOperatorName,
    getNetworkCountryIso: P.netCountryIso,
    getSimCarrierId: P.simCarrierId,
  };
  for (var m in ret) {
    try {
      TM[m].overloads.forEach(function (o) {
        var v = ret[m];
        o.implementation = function () { return v; };
      });
    } catch (e) {}
  }
  // carrierId=-1(未识别)时 name 必须 null——id 与 name 同进同退
  try {
    TM.getSimCarrierIdName.overloads.forEach(function (o) {
      o.implementation = function () {
        return P.simCarrierId >= 0 ? P.simOperatorName : null;
      };
    });
  } catch (e) {}

  // tower leak: serving cell exposes real mcc even under flight mode
  try { TM.getAllCellInfo.overloads.forEach(function (o) {
    o.implementation = function () {
      return Java.use("java.util.ArrayList").$new(); }; });
  } catch (e) {}
  try { TM.getCellLocation.overloads.forEach(function (o) {
    o.implementation = function () { return null; }; });
  } catch (e) {}

  // ---- SubscriptionInfo objects carry their own mcc/mnc/iso/name ----
  var SI = "android.telephony.SubscriptionInfo";
  retAll(SI, "getMcc", String(MCC), MCC);
  retAll(SI, "getMnc", String(MNC), MNC);
  retAll(SI, "getMccString", String(MCC), MCC);
  retAll(SI, "getMncString", P.simOperator.substring(3), MNC);
  retAll(SI, "getCountryIso", P.simCountryIso, 0);
  retAll(SI, "getCarrierName", P.simOperatorName, 0);
  retAll(SI, "getDisplayName", P.simOperatorName, 0);
  retAll(SI, "getSimOperatorName", P.simOperatorName, 0);

  // ---- Settings.Secure.getString -> android_id ----
  try {
    var SS = Java.use("android.provider.Settings$Secure");
    SS.getString.overloads.forEach(function (o) {
      o.implementation = function (cr, name) {
        return name === "android_id" ? P.androidId : o.call(this, cr, name);
      };
    });
  } catch (e) {}

  // ---- Build.* (+VERSION.* nested: RELEASE/SDK_INT/SECURITY_PATCH/…) ----
  try {
    var B = Java.use("android.os.Build");
    var V = Java.use("android.os.Build$VERSION");
    for (var f in P.build) {
      try {
        if (f.indexOf("VERSION.") === 0)
          V[f.substring(8)].value = P.build[f];
        else B[f].value = P.build[f];
      } catch (e) {}
    }
  } catch (e) {}

  // ---- SystemProperties.get ----
  try {
    var SP = Java.use("android.os.SystemProperties");
    SP.get.overloads.forEach(function (o) {
      o.implementation = function (k, d) {
        if (k in PROPS) return String(PROPS[k]);
        return arguments.length > 1 ? o.call(this, k, d) : o.call(this, k);
      };
    });
  } catch (e) {}

  // ---- interface enumeration: drop tun/ppp/wg ----
  try {
    var NI = Java.use("java.net.NetworkInterface");
    var Coll = Java.use("java.util.Collections");
    var AL = Java.use("java.util.ArrayList");
    NI.getNetworkInterfaces.implementation = function () {
      var en = NI.getNetworkInterfaces.call(NI);
      var out = AL.$new();
      if (en === null) return Coll.enumeration(out);
      while (en.hasMoreElements()) {
        var n = en.nextElement();
        if (!IF_BAD.test(n.getName())) out.add(n);
      }
      return Coll.enumeration(out);
    };
  } catch (e) {}

  // LinkProperties.getInterfaceName — second leak path for tun names
  try {
    var LP = Java.use("android.net.LinkProperties");
    LP.getInterfaceName.implementation = function () {
      var v = LP.getInterfaceName.call(this);
      return (v !== null && IF_BAD.test(v)) ? "wlan0" : v;
    };
  } catch (e) {}

  // ---- Locale / TimeZone coherence (only when persona supplies) ----
  if (P.locale) try {
    var Loc = Java.use("java.util.Locale");
    var lv = Loc.forLanguageTag(P.locale.replace("_", "-"));
    Loc.getDefault.overloads.forEach(function (o) {
      o.implementation = function () { return lv; };
    });
  } catch (e) {}
  if (P.tz) try {
    var TZC = Java.use("java.util.TimeZone");
    var tv = TZC.getTimeZone(P.tz);
    TZC.getDefault.overloads.forEach(function (o) {
      o.implementation = function () { return tv; };
    });
  } catch (e) {}

  // ---- installer source -> Play ----
  retAll("android.content.pm.InstallSourceInfo",
      "getInstallingPackageName", "com.android.vending", 0);
  retAll("android.content.pm.InstallSourceInfo",
      "getInitiatingPackageName", "com.android.vending", 0);
  retAll("android.content.pm.InstallSourceInfo",
      "getOriginatingPackageName", "com.android.vending", 0);
  retAll("android.app.ApplicationPackageManager",
      "getInstallerPackageName", "com.android.vending", 0);

  // ---- accounts visibility ----
  if (P.hideAccounts) try {
    var AM = Java.use("android.accounts.AccountManager");
    var Arr = Java.use("java.lang.reflect.Array");
    var AC = Java.use("android.accounts.Account");
    var empty = Arr.newInstance(AC.class, 0);
    ["getAccounts", "getAccountsByType", "getAccountsAsUser"]
      .forEach(function (m) {
        try { AM[m].overloads.forEach(function (o) {
          o.implementation = function () { return empty; };
        }); } catch (e) {}
      });
  } catch (e) {}

  send("persona armed: " + P.simOperator + "/" + P.simCountryIso
      + "/" + P.simOperatorName);

  // self-check through hooked APIs -> app's own files dir
  try {
    setTimeout(function () {
      var out = "";
      try {
        var ctx = Java.use("android.app.ActivityThread")
            .currentApplication();
        var sm = Java.cast(ctx.getSystemService(
            "telephony_subscription_service"),
            Java.use("android.telephony.SubscriptionManager"));
        var l = sm.getActiveSubscriptionInfoList();
        if (l !== null && l.size() > 0) {
          var s = Java.cast(l.get(0),
              Java.use("android.telephony.SubscriptionInfo"));
          out += "sub mcc=" + s.getMcc() + " mnc=" + s.getMnc()
              + " iso=" + s.getCountryIso()
              + " name=" + s.getCarrierName()
              + " disp=" + s.getDisplayName() + "\n";
        }
        var tm = Java.cast(ctx.getSystemService("phone"),
            Java.use("android.telephony.TelephonyManager"));
        var ci = tm.getAllCellInfo();
        out += "cell=" + (ci === null ? "null" : ci.size()) + "\n";
        out += "aid=" + Java.use("android.provider.Settings$Secure")
            .getString(ctx.getContentResolver(), "android_id") + "\n";
        var B = Java.use("android.os.Build");
        out += "model=" + B.MODEL.value
            + " fp=" + B.FINGERPRINT.value + "\n";
        out += "locale=" + Java.use("java.util.Locale").getDefault()
            .toString()
            + " tz=" + Java.use("java.util.TimeZone").getDefault().getID()
            + "\n";
        var SP = Java.use("android.os.SystemProperties");
        out += "prop.model=" + SP.get("ro.product.model")
            + " prop.fp=" + SP.get("ro.build.fingerprint") + "\n";
        // cloak self-verify: read maps through hooked Java path -> 0 agent hits
        try {
          var mapsNow = readAll("/proc/self/maps") || "";
          var hits = 0, ln2;
          var ls2 = mapsNow.split("\n");
          for (var i2 = 0; i2 < ls2.length; i2++)
            if (AGENT_RX.test(ls2[i2])) hits++;
          out += "cloak.maps hits=" + hits + "\n";
        } catch (e) { out += "cloak.maps err\n"; }
        // exec path: cat maps must hit the fake too; getprop -> persona value
        try {
          var RT = Java.use("java.lang.Runtime");
          var pr = RT.getRuntime().exec("cat /proc/self/maps");
          var rd = Java.use("java.io.BufferedReader").$new(
              Java.use("java.io.InputStreamReader").$new(pr.getInputStream()));
          var eh = 0, el;
          while ((el = rd.readLine()) !== null)
            if (AGENT_RX.test(el)) eh++;
          pr.waitFor();
          out += "cloak.exec hits=" + eh + "\n";
        } catch (e) { out += "cloak.exec err\n"; }
        try {
          var gp = RT.getRuntime().exec("getprop ro.product.model");
          var is2 = gp.getInputStream();
          var buf2 = Java.array("byte", new Array(64).fill(0));
          var n2 = is2.read(buf2);
          out += "exec.model=" + (n2 > 0
              ? Java.use("java.lang.String").$new(buf2, 0, n2).toString().trim()
              : "?") + "\n";
        } catch (e) { out += "exec.model err\n"; }
        // thread comm names: sweep once more (late agent threads), then
        // enumerate /proc/self/task -> 0 agent hits
        try { renameThreads(); } catch (e) {}
        try {
          var td = Java.use("java.io.File").$new("/proc/self/task").listFiles();
          var ch = 0, cn = "";
          for (var i3 = 0; i3 < td.length; i3++) {
            var nm3 = readAll(td[i3].getPath() + "/comm") || "";
            if (AGENT_RX.test(nm3)) { ch++; cn += " " + nm3.trim(); }
          }
          out += "cloak.comm hits=" + ch + (cn ? " [" + cn + " ]" : "") + "\n";
        } catch (e) { out += "cloak.comm err\n"; }
      } catch (e) { out += "err " + e; }
      try {
        var f = Java.use("java.io.FileOutputStream").$new(
            ctx === null ? "/data/local/tmp/selfcheck.txt"
            : ctx.getFilesDir().getPath() + "/selfcheck.txt");
        f.write(Java.use("java.lang.String").$new(out).getBytes());
        f.close();
      } catch (e) {}
    }, 2500);
  } catch (e) {}
});

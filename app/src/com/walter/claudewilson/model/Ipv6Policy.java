package com.walter.claudewilson.model;

import java.util.Locale;

/**
 * Pure policy &amp; shell script builder for persistent device-wide IPv6 suppression vs regional
 * IPv6 spoofing. Zero Android imports, zero CJK.
 *
 * <p>Default mode is {@link #MODE_OFF}: IPv6 is disabled across all non-loopback network
 * interfaces (Wi-Fi, cellular, tunnel, virtual, ethernet) and guarded by a zero-polling
 * {@code ip -6 monitor address link} watchdog so Android {@code netd} / {@code IpClient}
 * cannot silently re-enable IPv6 on network switches or airplane-mode toggles.
 */
public final class Ipv6Policy {
    public static final String MODE_OFF = "off";
    public static final String MODE_SPOOF = "spoof";

    public static final String DIR = "/data/local/tmp/claudewilson";
    public static final String MODE_PATH = DIR + "/ipv6.mode";
    public static final String GUARD_SCRIPT_PATH = DIR + "/ipv6-guard.sh";
    public static final String GUARD_PID_PATH = DIR + "/ipv6-guard.pid";
    public static final String LEGACY_SERVICE_SCRIPT = "/data/adb/service.d/99-disable-ipv6.sh";
    public static final String LEGACY_PID_PATH = "/data/local/tmp/.ipv6guard.pid";

    private Ipv6Policy() {
    }

    private static String shellStopLegacyCommand() {
        return "if [ -f " + LEGACY_PID_PATH + " ]; then "
                + "  P=$(cat " + LEGACY_PID_PATH + " 2>/dev/null); "
                + "  if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null "
                + "    && grep -qaE '99-disable-ipv6|(^|/)sh$' \"/proc/$P/cmdline\" 2>/dev/null; then "
                + "    pkill -P \"$P\" 2>/dev/null; kill \"$P\" 2>/dev/null; "
                + "  fi; "
                + "  rm -f " + LEGACY_PID_PATH + "; "
                + "fi; "
                + "for P in $(ps -ef 2>/dev/null | grep '99-disable-ipv6\\.sh' | grep -v grep | awk '{print $2}'); do "
                + "  pkill -P \"$P\" 2>/dev/null; kill \"$P\" 2>/dev/null; "
                + "done; ";
    }

    /** Parses stored mode text; defaults to {@link #MODE_OFF} on null/empty/unknown input. */
    public static String parseMode(String raw) {
        if (raw == null) {
            return MODE_OFF;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (MODE_SPOOF.equals(t) || "on".equals(t) || "true".equals(t) || "1".equals(t)) {
            return MODE_SPOOF;
        }
        return MODE_OFF;
    }

    /** True when IPv6 must remain disabled across kernel interfaces and target hooks. */
    public static boolean isDisabled(String mode) {
        return !MODE_SPOOF.equals(parseMode(mode));
    }

    /** Builds the idempotent root shell command for the requested IPv6 spoof state. */
    public static String shellSyncCommand(boolean spoofEnabled) {
        return spoofEnabled ? shellRestoreCommand() : shellDisableCommand();
    }

    /**
     * Disables IPv6 across internet/VPN interfaces while explicitly exempting loopback and active
     * cellular IMS/EIMS PDN interfaces (required by VoLTE, VoNR, and SMS-over-IMS on IPv6-only IMS
     * carriers). Runs a single-instance netlink watchdog so network switches cannot re-enable IPv6
     * on internet interfaces without ever breaking IMS.
     */
    public static String shellDisableCommand() {
        return "mkdir -p " + DIR + " && chmod 755 " + DIR + "; "
                + shellStopLegacyCommand()
                + "printf '%s\\n' '" + MODE_OFF + "' > " + MODE_PATH + " && chmod 644 " + MODE_PATH + "; "
                + "cat > " + GUARD_SCRIPT_PATH + ".tmp << 'CW_IPV6_EOF'\n"
                + "#!/system/bin/sh\n"
                + "MODE_FILE=" + MODE_PATH + "\n"
                + "PID_FILE=" + GUARD_PID_PATH + "\n"
                + "inet_rmnet_ifaces() {\n"
                + "  dumpsys connectivity 2>/dev/null | sed -n -E '/Capabilities:[^]]*[:& ]INTERNET([]& ]|$)/{/(extra: ims|Capabilities:[^]]*[:& ](IMS|EIMS)([]& ]|$))/d; s/.*InterfaceName:[[:space:]]*([^ ,}]+).*/\\1/p}' | sort -u\n"
                + "}\n"
                + "should_disable() {\n"
                + "  case \"$1\" in\n"
                + "    lo|all|default|dummy*|rmnet_ipa*|epdg*|r_rmnet_data*|rev_rmnet*|ipsec*|xfrm*|ims*|v4-rmnet*|v4-ccmni*) return 1 ;;\n"
                + "    rmnet_data*|ccmni*) case \" $INET_RMNET \" in *\" $1 \"*) return 0 ;; *) return 1 ;; esac ;;\n"
                + "  esac\n"
                + "  return 0\n"
                + "}\n"
                + "apply_off() {\n"
                + "  INET_RMNET=$(inet_rmnet_ifaces | tr '\\n' ' ')\n"
                + "  echo 0 > /proc/sys/net/ipv6/conf/default/disable_ipv6 2>/dev/null\n"
                + "  echo 0 > /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null\n"
                + "  echo 0 > /proc/sys/net/ipv6/conf/lo/disable_ipv6 2>/dev/null\n"
                + "  for d in /proc/sys/net/ipv6/conf/*; do\n"
                + "    n=\"${d##*/}\"\n"
                + "    if should_disable \"$n\"; then\n"
                + "      echo 1 > \"$d/disable_ipv6\" 2>/dev/null\n"
                + "    else\n"
                + "      case \"$n\" in rmnet_data*|rmnet_ipa*|ccmni*|epdg*|r_rmnet_data*|rev_rmnet*|ipsec*|xfrm*|ims*) echo 0 > \"$d/disable_ipv6\" 2>/dev/null ;; esac\n"
                + "    fi\n"
                + "  done\n"
                + "  for d in /sys/class/net/*; do\n"
                + "    n=\"${d##*/}\"\n"
                + "    should_disable \"$n\" || continue\n"
                + "    ip -6 addr flush dev \"$n\" scope global 2>/dev/null\n"
                + "  done\n"
                + "}\n"
                + "needs_off() {\n"
                + "  INET_RMNET=$(inet_rmnet_ifaces | tr '\\n' ' ')\n"
                + "  for d in /proc/sys/net/ipv6/conf/*; do\n"
                + "    n=\"${d##*/}\"\n"
                + "    should_disable \"$n\" || continue\n"
                + "    [ \"$(cat \"$d/disable_ipv6\" 2>/dev/null)\" = \"0\" ] && return 0\n"
                + "  done\n"
                + "  return 1\n"
                + "}\n"
                + "echo $$ > \"$PID_FILE\"\n"
                + "apply_off\n"
                + "while [ \"$(cat \"$MODE_FILE\" 2>/dev/null)\" = \"" + MODE_OFF + "\" ]; do\n"
                + "  ip -6 monitor address link 2>/dev/null | while IFS= read -r line; do\n"
                + "    [ \"$(cat \"$MODE_FILE\" 2>/dev/null)\" = \"" + MODE_OFF + "\" ] || exit 0\n"
                + "    case \"$line\" in\n"
                + "      *\" lo \"*|*\" lo:\"*|*\"Deleted \"*) continue ;;\n"
                + "    esac\n"
                + "    if needs_off; then\n"
                + "      apply_off\n"
                + "    fi\n"
                + "  done\n"
                + "  [ \"$(cat \"$MODE_FILE\" 2>/dev/null)\" = \"" + MODE_OFF + "\" ] || break\n"
                + "  if needs_off; then\n"
                + "    apply_off\n"
                + "  fi\n"
                + "  sleep 2\n"
                + "done\n"
                + "rm -f \"$PID_FILE\"\n"
                + "CW_IPV6_EOF\n"
                + "SCRIPT_CHANGED=1; "
                + "if [ -f " + GUARD_SCRIPT_PATH + " ] && cmp -s " + GUARD_SCRIPT_PATH + ".tmp " + GUARD_SCRIPT_PATH + " 2>/dev/null; then SCRIPT_CHANGED=0; fi; "
                + "chmod 755 " + GUARD_SCRIPT_PATH + ".tmp && mv -f " + GUARD_SCRIPT_PATH + ".tmp " + GUARD_SCRIPT_PATH + "; "
                + "INET_RMNET=$(dumpsys connectivity 2>/dev/null | sed -n -E '/Capabilities:[^]]*[:& ]INTERNET([]& ]|$)/{/(extra: ims|Capabilities:[^]]*[:& ](IMS|EIMS)([]& ]|$))/d; s/.*InterfaceName:[[:space:]]*([^ ,}]+).*/\\1/p}' | sort -u | tr '\\n' ' '); "
                + "echo 0 > /proc/sys/net/ipv6/conf/default/disable_ipv6 2>/dev/null; "
                + "echo 0 > /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null; "
                + "echo 0 > /proc/sys/net/ipv6/conf/lo/disable_ipv6 2>/dev/null; "
                + "for d in /proc/sys/net/ipv6/conf/*; do n=\"${d##*/}\"; case \"$n\" in lo|all|default|dummy*|rmnet_ipa*|v4-rmnet*|v4-ccmni*) continue ;; epdg*|r_rmnet_data*|rev_rmnet*|ipsec*|xfrm*|ims*) echo 0 > \"$d/disable_ipv6\" 2>/dev/null ;; rmnet_data*|ccmni*) case \" $INET_RMNET \" in *\" $n \"*) echo 1 > \"$d/disable_ipv6\" 2>/dev/null ;; *) echo 0 > \"$d/disable_ipv6\" 2>/dev/null ;; esac ;; *) echo 1 > \"$d/disable_ipv6\" 2>/dev/null ;; esac; done; "
                + "for d in /sys/class/net/*; do n=\"${d##*/}\"; case \"$n\" in lo|dummy*|rmnet_ipa*|epdg*|r_rmnet_data*|rev_rmnet*|ipsec*|xfrm*|ims*|v4-rmnet*|v4-ccmni*) continue ;; rmnet_data*|ccmni*) case \" $INET_RMNET \" in *\" $n \"*) ip -6 addr flush dev \"$n\" scope global 2>/dev/null ;; esac ;; *) ip -6 addr flush dev \"$n\" scope global 2>/dev/null ;; esac; done; "
                + "KEEP=''; "
                + "if [ \"$SCRIPT_CHANGED\" = \"0\" ] && [ -f " + GUARD_PID_PATH + " ]; then "
                + "  P=$(cat " + GUARD_PID_PATH + " 2>/dev/null); "
                + "  if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null && grep -qa 'ipv6-guard.sh' \"/proc/$P/cmdline\" 2>/dev/null; then KEEP=\"$P\"; fi; "
                + "fi; "
                + "for P in $(ps -ef 2>/dev/null | grep 'ipv6-guard.sh' | grep -v grep | awk '$3==1{print $2}'); do "
                + "  if [ \"$SCRIPT_CHANGED\" = \"0\" ] && [ -z \"$KEEP\" ]; then KEEP=\"$P\"; echo \"$P\" > " + GUARD_PID_PATH + "; "
                + "  elif [ \"$P\" != \"$KEEP\" ]; then pkill -P \"$P\" 2>/dev/null; kill \"$P\" 2>/dev/null; fi; "
                + "done; "
                + "if [ -z \"$KEEP\" ]; then "
                + "  for P in $(ps -ef 2>/dev/null | grep -E 'ipv6-guard\\.sh|ip -6 monitor address link' | grep -v grep | awk '{print $2}'); do kill \"$P\" 2>/dev/null; done; "
                + "  rm -f " + GUARD_PID_PATH + "; "
                + "  nohup sh " + GUARD_SCRIPT_PATH + " </dev/null >/dev/null 2>&1 & "
                + "fi; "
                + "echo ok";
    }

    /**
     * Stops all netlink watchdog instances and restores kernel IPv6 enablement so regional IPv6
     * spoofing can operate when the user explicitly turns the IPv6 toggle on.
     */
    public static String shellRestoreCommand() {
        return "mkdir -p " + DIR + " && chmod 755 " + DIR + "; "
                + "printf '%s\\n' '" + MODE_SPOOF + "' > " + MODE_PATH + " && chmod 644 " + MODE_PATH + "; "
                + shellStopLegacyCommand()
                + "if [ -f " + GUARD_PID_PATH + " ]; then "
                + "  P=$(cat " + GUARD_PID_PATH + " 2>/dev/null); "
                + "  if [ -n \"$P\" ]; then pkill -P \"$P\" 2>/dev/null; kill \"$P\" 2>/dev/null; fi; "
                + "  rm -f " + GUARD_PID_PATH + "; "
                + "fi; "
                + "for P in $(ps -ef 2>/dev/null | grep -E 'ipv6-guard\\.sh|ip -6 monitor address link' | grep -v grep | awk '{print $2}'); do "
                + "  kill \"$P\" 2>/dev/null; "
                + "done; "
                + "echo 0 > /proc/sys/net/ipv6/conf/default/disable_ipv6 2>/dev/null; "
                + "echo 0 > /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null; "
                + "for d in /proc/sys/net/ipv6/conf/*; do echo 0 > \"$d/disable_ipv6\" 2>/dev/null; done; "
                + "echo ok";
    }
}

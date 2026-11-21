package com.walter.claudewilson;

/**
 * Concurrent network probes used exclusively by Bridge in the shell app_process.
 *
 * <p>Implements {@link Runnable} directly (no anonymous inner classes or lambdas) so all network
 * bytecode remains strictly owned by {@code ShellNetworkProbe} under {@code build.py}'s audit.
 */
final class ShellNetworkProbe implements Runnable {
    private static final int TASK_EGRESS = 1;
    private static final int TASK_DNS = 2;
    private static final int TASK_V6 = 3;
    private static final int TASK_UDP = 4;
    private static final int TASK_QUICK_IPINFO = 5;
    private static final int TASK_QUICK_IPAPI = 6;

    private final int task;
    private final Object raceLock;
    private final String[] raceWinner;
    private final int[] raceDone;
    volatile String line = "";
    volatile String val1 = "";
    volatile String val2 = "";

    private ShellNetworkProbe(int task) {
        this(task, null, null, null);
    }

    private ShellNetworkProbe(int task, Object raceLock, String[] raceWinner, int[] raceDone) {
        this.task = task;
        this.raceLock = raceLock;
        this.raceWinner = raceWinner;
        this.raceDone = raceDone;
    }

    // All endpoints are neutral whoami/geoip services. Whitelist is by host
    // suffix — redirects re-checked, nothing else can ever be fetched.
    private static final String[] NET_HOSTS = {
            "ipinfo.io", "ip-api.com", "ipify.org", "icanhazip.com", "ident.me"};

    static String httpGet(String url) throws Exception {
        return httpGet(url, 4000, 4500);
    }

    static String httpGet(String url, int connectMs, int readMs) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(false);
        for (int hops = 0; hops < 4; hops++) {
            String host = c.getURL().getHost().toLowerCase(java.util.Locale.ROOT);
            boolean ok = false;
            for (String h : NET_HOSTS) if (host.equals(h) || host.endsWith("." + h)) ok = true;
            if (!ok) throw new SecurityException("host not whitelisted: " + host);
            int rc = c.getResponseCode();
            if (rc >= 300 && rc < 400 && c.getHeaderField("Location") != null) {
                c = (java.net.HttpURLConnection)
                        new java.net.URL(c.getHeaderField("Location")).openConnection();
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setInstanceFollowRedirects(false);
                continue;
            }
            java.io.InputStream in = rc < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 16384) b.write(buf, 0, n);
            in.close();
            return b.toString("UTF-8");
        }
        return "";
    }

    static String jfield(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(body);
        return m.find() ? m.group(1) : "";
    }

    static boolean jbool(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)")
                .matcher(body);
        return m.find() && "true".equals(m.group(1));
    }

    /** First "country" field of an ipinfo/ip-api lookup for an arbitrary IP (v4 or v6). */
    static String geoOf(String ip) {
        try {
            String cc = jfield(httpGet("https://ipinfo.io/" + ip + "/json", 3000, 3500), "country");
            if (!cc.isEmpty()) return cc;
        } catch (Throwable ignored) {
        }
        try {
            String j = httpGet("http://ip-api.com/json/" + ip + "?fields=status,countryCode", 3000, 3500);
            if ("success".equals(jfield(j, "status"))) {
                return jfield(j, "countryCode");
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Raw UDP DNS TXT probe — o-o.myaddr.l.google.com @ns1.google.com. */
    static String udpWhoami() throws Exception {
        java.net.InetAddress srv = java.net.InetAddress.getByName("ns1.google.com");
        java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
        int id = (int) (System.nanoTime() & 0xffff);
        q.write(id >> 8); q.write(id & 0xff);
        q.write(new byte[]{1, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String L : "o-o.myaddr.l.google.com".split("\\.")) {
            q.write(L.length());
            q.write(L.getBytes("US-ASCII"));
        }
        q.write(new byte[]{0, 0, 16, 0, 1});
        byte[] pkt = q.toByteArray();
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setSoTimeout(3000);
            s.send(new java.net.DatagramPacket(pkt, pkt.length, srv, 53));
            byte[] rb = new byte[1024];
            java.net.DatagramPacket rp = new java.net.DatagramPacket(rb, rb.length);
            s.receive(rp);
            int len = rp.getLength();
            int an = ((rb[6] & 0xff) << 8) | (rb[7] & 0xff);
            int off = 12;
            while (off < len && rb[off] != 0) off += (rb[off] & 0xff) + 1;
            off += 5;
            String found = "";
            for (int i = 0; i < an && off + 10 < len; i++) {
                if ((rb[off] & 0xc0) == 0xc0) off += 2;
                else { while (off < len && rb[off] != 0) off += (rb[off] & 0xff) + 1; off++; }
                int type = ((rb[off] & 0xff) << 8) | (rb[off + 1] & 0xff);
                int rdlen = ((rb[off + 8] & 0xff) << 8) | (rb[off + 9] & 0xff);
                if (type == 16 && rdlen > 1) {
                    int p = off + 10, end = p + rdlen;
                    while (p < end) {
                        int tl = rb[p] & 0xff;
                        String cs = new String(rb, p + 1, Math.min(tl, end - p - 1), "UTF-8");
                        if (cs.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return cs;
                        if (found.isEmpty()) found = cs;
                        p += 1 + tl;
                    }
                }
                off += 10 + rdlen;
            }
            return found.isEmpty() ? "no-txt" : found;
        }
    }

    /** RFC5389 STUN Binding Request -> XOR-MAPPED-ADDRESS (real WebRTC/ICE UDP egress). */
    static String stunProbe(String host, int port) throws Exception {
        java.net.InetAddress srv = java.net.InetAddress.getByName(host);
        byte[] tx = new byte[12];
        new java.util.Random().nextBytes(tx);
        java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
        q.write(new byte[]{0, 1, 0, 0, 0x21, 0x12, (byte) 0xa4, 0x42});
        q.write(tx);
        byte[] pkt = q.toByteArray();
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setSoTimeout(3000);
            s.send(new java.net.DatagramPacket(pkt, pkt.length, srv, port));
            byte[] rb = new byte[512];
            java.net.DatagramPacket rp = new java.net.DatagramPacket(rb, rb.length);
            s.receive(rp);
            int len = rp.getLength();
            if (len < 20 || (rb[0] & 0xff) != 1 || (rb[1] & 0xff) != 1) return "bad-resp";
            int off = 20;
            while (off + 4 <= len) {
                int at = ((rb[off] & 0xff) << 8) | (rb[off + 1] & 0xff);
                int al = ((rb[off + 2] & 0xff) << 8) | (rb[off + 3] & 0xff);
                if (at == 0x0020 && al >= 8 && rb[off + 5] == 0x01) {
                    int b0 = (rb[off + 8] & 0xff) ^ 0x21;
                    int b1 = (rb[off + 9] & 0xff) ^ 0x12;
                    int b2 = (rb[off + 10] & 0xff) ^ 0xa4;
                    int b3 = (rb[off + 11] & 0xff) ^ 0x42;
                    return b0 + "." + b1 + "." + b2 + "." + b3;
                }
                if (at == 0x0001 && al >= 8 && rb[off + 5] == 0x01) {
                    return (rb[off + 8] & 0xff) + "." + (rb[off + 9] & 0xff)
                            + "." + (rb[off + 10] & 0xff) + "." + (rb[off + 11] & 0xff);
                }
                off += 4 + ((al + 3) & ~3);
            }
            return "no-addr";
        }
    }

    private static String safeToken(String s) {
        if (s == null) return "";
        return s.trim().replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    @Override
    public void run() {
        switch (task) {
            case TASK_EGRESS:
                runEgress();
                break;
            case TASK_DNS:
                runDns();
                break;
            case TASK_V6:
                runV6();
                break;
            case TASK_UDP:
                runUdp();
                break;
            case TASK_QUICK_IPINFO:
                runQuickIpinfo();
                break;
            case TASK_QUICK_IPAPI:
                runQuickIpapi();
                break;
            default:
                break;
        }
    }

    private void runEgress() {
        String egIp = "", egCc = "", egCity = "", egTz = "", egOrg = "";
        boolean hosting = false, proxy = false;
        try {
            String j = httpGet("https://ipinfo.io/json", 4000, 4500);
            egIp = jfield(j, "ip");
            egCc = jfield(j, "country");
            egCity = jfield(j, "city");
            egTz = jfield(j, "timezone");
            egOrg = jfield(j, "org");
        } catch (Throwable ignored) {
        }
        if (egIp.isEmpty() || egCc.isEmpty() || egOrg.isEmpty()) {
            try {
                String j = httpGet("http://ip-api.com/json/?fields=status,query,"
                        + "countryCode,city,timezone,as,isp,org,proxy,hosting", 4000, 4500);
                if ("success".equals(jfield(j, "status"))) {
                    if (egIp.isEmpty()) egIp = jfield(j, "query");
                    if (egCc.isEmpty()) egCc = jfield(j, "countryCode");
                    if (egCity.isEmpty()) egCity = jfield(j, "city");
                    if (egTz.isEmpty()) egTz = jfield(j, "timezone");
                    if (egOrg.isEmpty()) egOrg = jfield(j, "as");
                    if (egOrg.isEmpty()) egOrg = jfield(j, "isp");
                    if (egOrg.isEmpty()) egOrg = jfield(j, "org");
                    hosting = jbool(j, "hosting");
                    proxy = jbool(j, "proxy");
                }
            } catch (Throwable ignored) {
            }
        }
        if (egIp.isEmpty()) {
            try {
                egIp = httpGet("https://api.ipify.org", 3500, 3500).trim();
            } catch (Throwable ignored) {
            }
        }
        val1 = egIp;
        val2 = egCc;
        line = "EGRESS ip=" + safeToken(egIp)
                + " cc=" + safeToken(egCc)
                + " city=" + safeToken(egCity)
                + " tz=" + safeToken(egTz)
                + " hosting=" + (hosting ? "1" : "0")
                + " proxy=" + (proxy ? "1" : "0")
                + " org=" + (egOrg == null ? "" : egOrg.trim());
    }

    private void runDns() {
        StringBuilder fb = new StringBuilder();
        for (String h : new String[]{"www.google.com", "www.youtube.com",
                "www.cloudflare.com"}) {
            try {
                StringBuilder sb = new StringBuilder();
                for (java.net.InetAddress a : java.net.InetAddress.getAllByName(h)) {
                    sb.append(a.getHostAddress()).append('+');
                }
                fb.append(h).append('=').append(sb).append(' ');
            } catch (Throwable t) {
                fb.append(h).append("=err ");
            }
        }
        line = "DNSFAKE " + fb.toString().trim();
    }

    private void runV6() {
        String v6 = "none";
        try {
            String raw = httpGet("https://api6.ipify.org", 3000, 3500).trim();
            if (raw.contains(":")) {
                v6 = raw;
            }
        } catch (Throwable ignored) {
        }
        String v6cc = (!"none".equals(v6) && v6.contains(":")) ? geoOf(v6) : "";
        StringBuilder loc = new StringBuilder();
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> e =
                    java.net.NetworkInterface.getNetworkInterfaces();
                    e.hasMoreElements();) {
                java.net.NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(
                        ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet6Address)) continue;
                    if (a.isLinkLocalAddress() || a.isLoopbackAddress()
                            || a.isSiteLocalAddress()) continue;
                    String s = a.getHostAddress();
                    int pct = s.indexOf('%');
                    if (pct > 0) s = s.substring(0, pct);
                    loc.append(ni.getName()).append('=').append(s).append(' ');
                }
            }
        } catch (Throwable ignored) {
        }
        line = "V6 ip=" + safeToken(v6)
                + " cc=" + safeToken(v6cc)
                + " local=" + loc.toString().trim();
    }

    private void runUdp() {
        String u = "", via = "";
        try { u = stunProbe("stun.l.google.com", 19302); via = "stun"; }
        catch (Throwable t) {
            try { u = stunProbe("stun.cloudflare.com", 3478); via = "stun"; }
            catch (Throwable t2) { /* fallback to dns53 */ }
        }
        if (u.isEmpty() || !u.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            try { u = udpWhoami(); via = "dns53"; }
            catch (java.net.SocketTimeoutException t) { u = "timeout"; via = ""; }
            catch (Throwable t) { u = "err"; via = ""; }
        }
        val1 = u;
        val2 = via;
    }

    static void netProbe() {
        ShellNetworkProbe pEg = new ShellNetworkProbe(TASK_EGRESS);
        ShellNetworkProbe pDns = new ShellNetworkProbe(TASK_DNS);
        ShellNetworkProbe pV6 = new ShellNetworkProbe(TASK_V6);
        ShellNetworkProbe pUdp = new ShellNetworkProbe(TASK_UDP);

        Thread tEg = new Thread(pEg, "np-eg");
        Thread tDns = new Thread(pDns, "np-dns");
        Thread tV6 = new Thread(pV6, "np-v6");
        Thread tUdp = new Thread(pUdp, "np-udp");
        tEg.setDaemon(true);
        tDns.setDaemon(true);
        tV6.setDaemon(true);
        tUdp.setDaemon(true);
        tEg.start();
        tDns.start();
        tV6.start();
        tUdp.start();

        joinQuiet(tEg, 9500L);
        joinQuiet(tDns, 5000L);
        joinQuiet(tV6, 7000L);
        joinQuiet(tUdp, 8500L);

        System.out.println(pEg.line.isEmpty()
                ? "EGRESS ip= cc= city= tz= hosting=0 proxy=0 org=" : pEg.line);
        System.out.println(pDns.line.isEmpty() ? "DNSFAKE" : pDns.line);
        System.out.println(pV6.line.isEmpty() ? "V6 ip=none cc= local=" : pV6.line);

        String u = pUdp.val1.isEmpty() ? "timeout" : pUdp.val1;
        String via = pUdp.val2;
        String ucc = "";
        if (u.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            ucc = u.equals(pEg.val1) && !pEg.val2.isEmpty() ? pEg.val2 : geoOf(u);
        }
        System.out.println("UDP ip=" + safeToken(u) + " cc=" + safeToken(ucc)
                + (via.isEmpty() ? "" : " via=" + via));
    }

    private void publishRaceResult(String ip, String cc) {
        if (raceLock == null || raceWinner == null || raceDone == null) {
            return;
        }
        String cleanIp = ip == null ? "" : ip.trim();
        String cleanCc = cc == null ? "" : cc.trim().toUpperCase(java.util.Locale.ROOT);
        synchronized (raceLock) {
            if (cleanCc.length() == 2 && raceWinner[1].isEmpty()) {
                raceWinner[0] = cleanIp;
                raceWinner[1] = cleanCc;
            } else if (!cleanIp.isEmpty() && raceWinner[0].isEmpty()) {
                raceWinner[0] = cleanIp;
            }
            raceDone[0]++;
            raceLock.notifyAll();
        }
    }

    private void runQuickIpinfo() {
        String ip = "";
        String cc = "";
        try {
            String j = httpGet("https://ipinfo.io/json", 2000, 2200);
            ip = jfield(j, "ip");
            cc = jfield(j, "country");
        } catch (Throwable ignored) {
        }
        publishRaceResult(ip, cc);
    }

    private void runQuickIpapi() {
        String ip = "";
        String cc = "";
        try {
            String j = httpGet("http://ip-api.com/json/?fields=status,query,countryCode",
                    2000, 2200);
            if ("success".equals(jfield(j, "status"))) {
                ip = jfield(j, "query");
                cc = jfield(j, "countryCode");
            }
        } catch (Throwable ignored) {
        }
        publishRaceResult(ip, cc);
    }

    /**
     * Concurrent dual-source egress race (ipinfo.io vs ip-api.com): returns {@code [ip, cc]} as
     * soon as the first valid 2-letter country code arrives (typically 150-300ms).
     */
    static String[] egressQuickRace(long maxWaitMs) {
        final Object lock = new Object();
        final String[] winner = new String[]{"", ""};
        final int[] done = new int[]{0};
        Thread t1 = new Thread(new ShellNetworkProbe(TASK_QUICK_IPINFO, lock, winner, done),
                "np-q1");
        Thread t2 = new Thread(new ShellNetworkProbe(TASK_QUICK_IPAPI, lock, winner, done),
                "np-q2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        long deadline = System.currentTimeMillis() + Math.max(500L, maxWaitMs);
        synchronized (lock) {
            while (winner[1].isEmpty() && done[0] < 2) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0L) {
                    break;
                }
                try {
                    lock.wait(rem);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            return new String[]{safeToken(winner[0]), safeToken(winner[1])};
        }
    }

    /** Fast single-shot public IP + country code check for CLI and Unlock verify. */
    static void egressQuick() {
        String[] res = egressQuickRace(2600L);
        System.out.println("EGRESS ip=" + res[0] + " cc=" + res[1]);
    }

    private static void joinQuiet(Thread t, long timeoutMs) {
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
    }
}

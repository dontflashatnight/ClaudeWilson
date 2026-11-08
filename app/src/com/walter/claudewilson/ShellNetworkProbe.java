package com.walter.claudewilson;

/** Network probes used exclusively by Bridge in the shell app_process. */
final class ShellNetworkProbe {
    private ShellNetworkProbe() {}

    // ============ net probe (egress leak panel) ============
    // All endpoints are neutral whoami/geoip services. Whitelist is by host
    // suffix — redirects re-checked, nothing else can ever be fetched.

    private static final String[] NET_HOSTS = {
            "ipinfo.io", "ip-api.com", "ipify.org", "icanhazip.com", "ident.me"};

    static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setInstanceFollowRedirects(false);          // follow manually, re-check host
        for (int hops = 0; hops < 4; hops++) {
            String host = c.getURL().getHost().toLowerCase(java.util.Locale.ROOT);
            boolean ok = false;
            for (String h : NET_HOSTS) if (host.equals(h) || host.endsWith("." + h)) ok = true;
            if (!ok) throw new SecurityException("host not whitelisted: " + host);
            int rc = c.getResponseCode();
            if (rc >= 300 && rc < 400 && c.getHeaderField("Location") != null) {
                c = (java.net.HttpURLConnection)
                        new java.net.URL(c.getHeaderField("Location")).openConnection();
                c.setConnectTimeout(7000); c.setReadTimeout(7000);
                c.setInstanceFollowRedirects(false);
                continue;
            }
            java.io.InputStream in = rc < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
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

    /** first "country" field of an ipinfo lookup for an arbitrary IP. */
    static String geoOf(String ip) {
        try { return jfield(httpGet("https://ipinfo.io/" + ip + "/json"), "country"); }
        catch (Throwable t) { return ""; }
    }

    /** raw UDP DNS TXT probe — o-o.myaddr.l.google.com @ns1.google.com
     *  answers with the client egress IP as seen over pure UDP. This is the
     *  same path STUN/ICE takes (modulo port), i.e. the WebRTC leak probe. */
    static String udpWhoami() throws Exception {
        java.net.InetAddress srv = java.net.InetAddress.getByName("ns1.google.com");
        java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
        int id = (int) (System.nanoTime() & 0xffff);
        q.write(id >> 8); q.write(id & 0xff);
        q.write(new byte[]{1, 0, 0, 1, 0, 0, 0, 0, 0, 0});      // RD, QD=1
        for (String L : "o-o.myaddr.l.google.com".split("\\.")) {
            q.write(L.length());
            q.write(L.getBytes("US-ASCII"));
        }
        q.write(new byte[]{0, 0, 16, 0, 1});                    // name-end,TXT,IN
        byte[] pkt = q.toByteArray();
        java.net.DatagramSocket s = new java.net.DatagramSocket();
        s.setSoTimeout(5000);
        s.send(new java.net.DatagramPacket(pkt, pkt.length, srv, 53));
        byte[] rb = new byte[1024];
        java.net.DatagramPacket rp = new java.net.DatagramPacket(rb, rb.length);
        s.receive(rp);
        int len = rp.getLength();
        int an = ((rb[6] & 0xff) << 8) | (rb[7] & 0xff);
        int off = 12;
        while (off < len && rb[off] != 0) off += (rb[off] & 0xff) + 1;
        off += 5;                                               // qname NUL+qtype+qclass
        String found = "";
        for (int i = 0; i < an && off + 10 < len; i++) {
            if ((rb[off] & 0xc0) == 0xc0) off += 2;
            else { while (off < len && rb[off] != 0) off += (rb[off] & 0xff) + 1; off++; }
            int type = ((rb[off] & 0xff) << 8) | (rb[off + 1] & 0xff);
            int rdlen = ((rb[off + 8] & 0xff) << 8) | (rb[off + 9] & 0xff);
            if (type == 16 && rdlen > 1) {
                // A TXT record is a list of char-strings; the answer we want is
                // the egress IP one, not "edns0-client-subnet" or the subnet it
                // announces. Keep scanning and prefer an IPv4-looking string.
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

    /** RFC5389 STUN Binding Request → XOR-MAPPED-ADDRESS. This is the real
     *  WebRTC/ICE egress probe — UDP to a foreign STUN port, same path
     *  T-1's voice transport would take. */
    static String stunProbe(String host, int port) throws Exception {
        java.net.InetAddress srv = java.net.InetAddress.getByName(host);
        byte[] tx = new byte[12];
        new java.util.Random().nextBytes(tx);
        java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
        q.write(new byte[]{0, 1, 0, 0, 0x21, 0x12, (byte) 0xa4, 0x42});
        q.write(tx);
        byte[] pkt = q.toByteArray();
        java.net.DatagramSocket s = new java.net.DatagramSocket();
        s.setSoTimeout(4000);
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
            if (at == 0x0020 && al >= 8 && rb[off + 5] == 0x01) {   // XOR-MAPPED v4
                int b0 = (rb[off + 8] & 0xff) ^ 0x21;
                int b1 = (rb[off + 9] & 0xff) ^ 0x12;
                int b2 = (rb[off + 10] & 0xff) ^ 0xa4;
                int b3 = (rb[off + 11] & 0xff) ^ 0x42;
                return b0 + "." + b1 + "." + b2 + "." + b3;
            }
            if (at == 0x0001 && al >= 8 && rb[off + 5] == 0x01) {   // MAPPED-ADDRESS
                return (rb[off + 8] & 0xff) + "." + (rb[off + 9] & 0xff)
                        + "." + (rb[off + 10] & 0xff) + "." + (rb[off + 11] & 0xff);
            }
            off += 4 + ((al + 3) & ~3);
        }
        return "no-addr";
    }

    static void netProbe() {
        // TCP egress (geo + ASN) — what the server sees on the connection. ip-api is the
        // independent fallback because ipify returns an IP only and cannot fill ASN/org.
        String egIp = "", egCc = "", egOrg = "";
        try {
            String j = httpGet("https://ipinfo.io/json");
            egIp = jfield(j, "ip"); egCc = jfield(j, "country");
            egOrg = jfield(j, "org");
        } catch (Throwable t) {
            // The redundant lookup below fills all three fields when ipinfo is unreachable.
        }
        if (egIp.isEmpty() || egCc.isEmpty() || egOrg.isEmpty()) {
            try {
                String j = httpGet("http://ip-api.com/json/?fields=status,query,"
                        + "countryCode,as,org,isp");
                if ("success".equals(jfield(j, "status"))) {
                    if (egIp.isEmpty()) egIp = jfield(j, "query");
                    if (egCc.isEmpty()) egCc = jfield(j, "countryCode");
                    if (egOrg.isEmpty()) egOrg = jfield(j, "as");
                    if (egOrg.isEmpty()) egOrg = jfield(j, "isp");
                    if (egOrg.isEmpty()) egOrg = jfield(j, "org");
                }
            } catch (Throwable t) {
                // Last resort below still reports the egress IP when geo/ASN are unavailable.
            }
        }
        if (egIp.isEmpty()) {
            try { egIp = httpGet("https://api.ipify.org").trim(); }
            catch (Throwable t2) { /* fall through */ }
        }
        System.out.println("EGRESS ip=" + egIp + " cc=" + egCc + " org=" + egOrg);

        // Foreign-path DNS: proxy-routed foreign domains must come back fake-ip
        // (198.18.x) = no external lookup happens at all — the node resolves.
        // Multiple foreign domains to gauge fake-ip coverage breadth.
        StringBuilder fb = new StringBuilder();
        for (String h : new String[]{"www.google.com", "www.youtube.com",
                "www.cloudflare.com"}) {
            try {
                StringBuilder sb = new StringBuilder();
                for (java.net.InetAddress a
                        : java.net.InetAddress.getAllByName(h))
                    sb.append(a.getHostAddress()).append('+');
                fb.append(h).append('=').append(sb).append(' ');
            } catch (Throwable t) {
                fb.append(h).append("=err ");
            }
        }
        System.out.println("DNSFAKE " + fb.toString().trim());

        // v6-only endpoint — reachable = a v6 path exists; compare vs local
        // global v6 addrs to tell tunnel/relay from the device's real address.
        String v6 = "none";
        try { v6 = httpGet("https://api6.ipify.org").trim(); }
        catch (Throwable ignored) {}
        StringBuilder loc = new StringBuilder();
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> e =
                    java.net.NetworkInterface.getNetworkInterfaces();
                    e.hasMoreElements();) {
                java.net.NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a
                        : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet6Address)) continue;
                    if (a.isLinkLocalAddress() || a.isLoopbackAddress()
                            || a.isSiteLocalAddress()) continue;
                    String s = a.getHostAddress();
                    int pct = s.indexOf('%');
                    if (pct > 0) s = s.substring(0, pct);
                    loc.append(ni.getName()).append('=').append(s).append(' ');
                }
            }
        } catch (Throwable ignored) {}
        System.out.println("V6 ip=" + v6 + " local=" + loc.toString().trim());

        // UDP data-plane — real STUN binding to a foreign STUN server = the
        // WebRTC/ICE path. DNS53 fallback only marks whether port-53 is up.
        String u = "", via = "";
        try { u = stunProbe("stun.l.google.com", 19302); via = "stun"; }
        catch (Throwable t) {
            try { u = stunProbe("stun.cloudflare.com", 3478); via = "stun"; }
            catch (Throwable t2) { /* fall to dns53 */ }
        }
        if (u.isEmpty() || !u.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            try { u = udpWhoami(); via = "dns53"; }
            catch (java.net.SocketTimeoutException t) { u = "timeout"; via = ""; }
            catch (Throwable t) { u = "err"; via = ""; }
        }
        String ucc = u.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") ? geoOf(u) : "";
        System.out.println("UDP ip=" + u + " cc=" + ucc
                + (via.isEmpty() ? "" : " via=" + via));
    }

}

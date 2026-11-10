package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Virtual BLE neighbourhood. The catalog chooses device names by region; the seed chooses how
 * many appear, addresses, RSSI and tx power, so two personas in the same city do not advertise
 * the same fixed set.
 */
public final class BleProfile {
    public static final class Template {
        public final String name;
        public final int txPower;
        public final int weight;

        public Template(String name, int txPower, int weight) {
            this.name = name == null ? "" : name;
            this.txPower = txPower;
            this.weight = Math.max(1, weight);
        }
    }

    public static final class Device {
        public final String name;
        public final String address;
        public final int rssi;
        public final int txPower;
        public final boolean bonded;

        public Device(String name, String address, int rssi, int txPower, boolean bonded) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address.toUpperCase(Locale.ROOT);
            this.rssi = rssi;
            this.txPower = txPower;
            this.bonded = bonded;
        }
    }

    private BleProfile() {
    }

    public static List<Device> forRegion(long seed, int count) {
        return forRegion(seed, count, new ArrayList<Template>());
    }

    public static List<Device> forRegion(long seed, int count, List<Template> templates) {
        List<Device> out = new ArrayList<>();
        Random random = new Random(seed ^ 0x424C45L);
        List<Template> pool = templates == null || templates.isEmpty() ? fallback() : templates;
        int total = Math.max(2, Math.min(6, count + random.nextInt(4) - 1));
        for (int i = 0; i < total; i++) {
            Template template = pick(pool, random);
            int rssi = -46 - random.nextInt(38);
            String address = address(random);
            out.add(new Device(template.name, address, rssi, template.txPower, i == 0));
        }
        return out;
    }

    public static String json(List<Device> devices) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"name\":\"").append(escape(device.name))
                    .append("\",\"address\":\"").append(escape(device.address))
                    .append("\",\"rssi\":").append(device.rssi)
                    .append(",\"txPower\":").append(device.txPower)
                    .append(",\"bonded\":").append(device.bonded)
                    .append('}');
        }
        return out.append(']').toString();
    }

    private static List<Template> fallback() {
        List<Template> out = new ArrayList<>();
        out.add(new Template("AirPods Pro", -1, 4));
        out.add(new Template("Mi Band 8", 0, 4));
        out.add(new Template("Galaxy Watch6", -2, 3));
        out.add(new Template("Tile Mate", -6, 2));
        return out;
    }

    private static Template pick(List<Template> pool, Random random) {
        int total = 0;
        for (Template template : pool) {
            total += template.weight;
        }
        int value = random.nextInt(Math.max(1, total));
        for (Template template : pool) {
            value -= template.weight;
            if (value < 0) {
                return template;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static String address(Random random) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format(Locale.ROOT, "%02X", random.nextInt(256)));
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

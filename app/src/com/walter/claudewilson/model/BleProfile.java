package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Virtual BLE neighbourhood, adapted from VirtualRegion's bonded/scan result model: a small set
 * of named devices with valid public addresses and RSSI. No real BLE scan is performed.
 */
public final class BleProfile {
    public static final class Device {
        public final String name;
        public final String address;
        public final int rssi;
        public final boolean bonded;

        public Device(String name, String address, int rssi, boolean bonded) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address.toUpperCase(Locale.ROOT);
            this.rssi = rssi;
            this.bonded = bonded;
        }
    }

    private static final String[] NAMES = {
            "AirPods Pro", "Mi Band 8", "Galaxy Watch6", "Tile Mate", "WH-1000XM5"
    };

    private BleProfile() {
    }

    public static List<Device> forRegion(long seed, int count) {
        List<Device> out = new ArrayList<>();
        Random random = new Random(seed ^ 0x424C45L);
        int total = Math.max(1, Math.min(NAMES.length, count));
        for (int i = 0; i < total; i++) {
            String address = address(random);
            int rssi = -48 - random.nextInt(36);
            out.add(new Device(NAMES[i], address, rssi, i == 0));
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
                    .append(",\"bonded\":").append(device.bonded)
                    .append('}');
        }
        return out.append(']').toString();
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

package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reference data, loaded once and shared. Kept out of the rule classes so that changing a path
 * list or a country table does not mean editing (and recompiling) Java, and so tests can build
 * a table inline instead of depending on an APK asset.
 *
 * <p>Format is one entry per line; '#' starts a comment; {@code key=value} for the tables.
 */
public final class Tables {
    public static final String SU_PATHS = "su-paths";
    public static final String RW_DIRS = "rw-dirs";
    public static final String PACKAGE_KEYWORDS = "package-keywords";
    public static final String LABEL_KEYWORDS = "label-keywords";
    public static final String EMULATOR_SIGNATURES = "emulator-signatures";
    public static final String VENDOR_WORDS = "vendor-words";
    public static final String ROOT_PACKAGES = "root-packages";
    public static final String MODERN_PACKAGES = "modern-packages";
    public static final String LEAKED_CAS = "leaked-cas";
    public static final String VENDOR_FAMILIES = "vendor-families";
    public static final String PERSONAS = "personas";
    public static final String MCC = "mcc";
    public static final String CARRIERS = "carriers";

    public static final Tables EMPTY = parse(Collections.<String, String>emptyMap());

    private final String version;
    private final List<String> suPaths;
    private final List<String> rwDirs;
    private final List<String> packageKeywords;
    private final List<String> labelKeywords;
    private final List<String> emulatorSignatures;
    private final List<String> vendorWords;
    private final List<String> rootPackages;
    private final List<String> modernPackages;
    private final List<String> leakedCas;
    private final Map<String, String> vendorFamilies;
    private final List<String[]> personas;
    private final Map<String, String> mccToIso;
    private final Map<String, String> carrierKeywords;

    private Tables(String version, List<String> suPaths, List<String> rwDirs,
                   List<String> packageKeywords, List<String> labelKeywords,
                   List<String> emulatorSignatures, List<String> vendorWords,
                   List<String> rootPackages, List<String> modernPackages,
                   List<String> leakedCas, Map<String, String> vendorFamilies,
                   List<String[]> personas,
                   Map<String, String> mccToIso, Map<String, String> carrierKeywords) {
        this.version = version;
        this.suPaths = Collections.unmodifiableList(suPaths);
        this.rwDirs = Collections.unmodifiableList(rwDirs);
        this.packageKeywords = Collections.unmodifiableList(packageKeywords);
        this.labelKeywords = Collections.unmodifiableList(labelKeywords);
        this.emulatorSignatures = Collections.unmodifiableList(emulatorSignatures);
        this.vendorWords = Collections.unmodifiableList(vendorWords);
        this.rootPackages = Collections.unmodifiableList(rootPackages);
        this.modernPackages = Collections.unmodifiableList(modernPackages);
        this.leakedCas = Collections.unmodifiableList(leakedCas);
        this.vendorFamilies = Collections.unmodifiableMap(vendorFamilies);
        this.personas = Collections.unmodifiableList(personas);
        this.mccToIso = Collections.unmodifiableMap(mccToIso);
        this.carrierKeywords = Collections.unmodifiableMap(carrierKeywords);
    }

    public static Tables parse(Map<String, String> raw) {
        String version = "?";
        List<String> suPaths = new ArrayList<String>();
        List<String> rwDirs = new ArrayList<String>();
        List<String> packageKeywords = new ArrayList<String>();
        List<String> labelKeywords = new ArrayList<String>();
        List<String> emulatorSignatures = new ArrayList<String>();
        List<String> vendorWords = new ArrayList<String>();
        List<String> rootPackages = new ArrayList<String>();
        List<String> modernPackages = new ArrayList<String>();
        List<String> leakedCas = new ArrayList<String>();
        Map<String, String> vendorFamilies = new LinkedHashMap<String, String>();
        List<String[]> personas = new ArrayList<String[]>();
        Map<String, String> mccToIso = new LinkedHashMap<String, String>();
        Map<String, String> carrierKeywords = new LinkedHashMap<String, String>();
        if (raw != null) {
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                String name = entry.getKey();
                String text = entry.getValue() == null ? "" : entry.getValue();
                for (String rawLine : text.split("\n")) {
                    String line = rawLine.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("#")) {
                        String declared = declaredVersion(line);
                        if (declared != null) {
                            version = declared;
                        }
                        continue;
                    }
                    if (SU_PATHS.equals(name)) {
                        suPaths.add(line);
                    } else if (RW_DIRS.equals(name)) {
                        rwDirs.add(line);
                    } else if (PACKAGE_KEYWORDS.equals(name)) {
                        addUnique(packageKeywords, line.toLowerCase(Locale.ROOT));
                    } else if (LABEL_KEYWORDS.equals(name)) {
                        addUnique(labelKeywords, line.toLowerCase(Locale.ROOT));
                    } else if (EMULATOR_SIGNATURES.equals(name)) {
                        emulatorSignatures.add(line.toLowerCase(Locale.ROOT));
                    } else if (VENDOR_WORDS.equals(name)) {
                        vendorWords.add(line.toLowerCase(Locale.ROOT));
                    } else if (ROOT_PACKAGES.equals(name)) {
                        addUnique(rootPackages, line.toLowerCase(Locale.ROOT));
                    } else if (MODERN_PACKAGES.equals(name)) {
                        addUnique(modernPackages, line.toLowerCase(Locale.ROOT));
                    } else if (LEAKED_CAS.equals(name)) {
                        addUnique(leakedCas, line);
                    } else if (VENDOR_FAMILIES.equals(name)) {
                        putPair(vendorFamilies, line.toLowerCase(Locale.ROOT));
                    } else if (PERSONAS.equals(name)) {
                        String[] fields = line.split("\\|", -1);
                        if (fields.length >= 14) {
                            personas.add(fields);
                        }
                    } else if (MCC.equals(name)) {
                        putPair(mccToIso, line);
                    } else if (CARRIERS.equals(name)) {
                        putPair(carrierKeywords, line);
                    }
                }
            }
        }
        return new Tables(version, suPaths, rwDirs, packageKeywords, labelKeywords,
                emulatorSignatures, vendorWords, rootPackages, modernPackages, leakedCas,
                vendorFamilies, personas, mccToIso, carrierKeywords);
    }

    private static void addUnique(List<String> target, String value) {
        if (!target.contains(value)) {
            target.add(value);
        }
    }

    private static void putPair(Map<String, String> target, String line) {
        int split = line.indexOf('=');
        if (split > 0) {
            target.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
    }

    /** Recognises "# catalog 2026-11-02" and returns the trailing words. */
    private static String declaredVersion(String line) {
        String[] parts = line.substring(1).trim().split("\\s+");
        if (parts.length < 2 || !"catalog".equalsIgnoreCase(parts[0])) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(parts[i]);
        }
        return out.toString();
    }

    public String version() {
        return version;
    }

    public List<String> suPaths() {
        return suPaths;
    }

    public List<String> rwDirs() {
        return rwDirs;
    }

    public List<String> packageKeywords() {
        return packageKeywords;
    }

    public List<String> labelKeywords() {
        return labelKeywords;
    }

    public List<String> emulatorSignatures() {
        return emulatorSignatures;
    }

    public List<String> vendorWords() {
        return vendorWords;
    }

    public List<String> rootPackages() {
        return rootPackages;
    }

    public List<String> modernPackages() {
        return modernPackages;
    }

    /** Subject DNs of certificate authorities that should not be trusted. */
    public List<String> leakedCas() {
        return leakedCas;
    }

    /** Sub-brand to parent brand, e.g. {@code redmi=xiaomi}. */
    public Map<String, String> vendorFamilies() {
        return vendorFamilies;
    }

    /** Device presets: label, manufacturer, brand, model, device, product, hardware, board,
     *  fingerprint, build id, incremental, release, sdk, security patch. */
    public List<String[]> personas() {
        return personas;
    }

    public Map<String, String> mccToIso() {
        return mccToIso;
    }

    public Map<String, String> carrierKeywords() {
        return carrierKeywords;
    }

    /** True when no reference data was loaded; rules must then report unknown, not clean. */
    public boolean isEmpty() {
        return suPaths.isEmpty() && rwDirs.isEmpty() && mccToIso.isEmpty()
                && carrierKeywords.isEmpty() && packageKeywords.isEmpty()
                && labelKeywords.isEmpty() && emulatorSignatures.isEmpty()
                && vendorWords.isEmpty() && rootPackages.isEmpty() && modernPackages.isEmpty()
                && leakedCas.isEmpty() && vendorFamilies.isEmpty() && personas.isEmpty();
    }
}

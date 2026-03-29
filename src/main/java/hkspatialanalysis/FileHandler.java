package hkspatialanalysis;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Reads and writes spatial data CSV files.
 *
 * Supported column layouts (auto-detected):
 *
 *   2-D  : NAME, EASTING, NORTHING, CATEGORY, DESCRIPTION
 *   3-D  : NAME, EASTING, NORTHING, ELEVATION, CATEGORY, DESCRIPTION
 *
 * School dataset layout (also auto-detected via header keywords):
 *   ENGLISH NAME, EASTING, NORTHING, STUDENTS GENDER, DISTRICT
 *   → mapped as  NAME,     EASTING,  NORTHING,  CATEGORY,         DESCRIPTION
 *
 * Handles RFC-4180 quoted fields (e.g. "WAH YAN COLLEGE, KOWLOON").
 *
 * The 3-D format is detected by checking whether column 4 (0-indexed: 3)
 * is a pure decimal number; if so it is treated as ELEVATION.
 */
public class FileHandler {

    // ── Dialog wrappers ───────────────────────────────────────────────────────

    public static List<SpatialPoint> readCSVWithDialog() throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open Spatial Data File");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fc.setAcceptAllFileFilterUsed(true);
        if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null;
        return readCSV(fc.getSelectedFile().getAbsolutePath());
    }

    public static boolean writeCSVWithDialog(List<SpatialPoint> points) throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Spatial Data File");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fc.setAcceptAllFileFilterUsed(false);
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return false;
        String path = fc.getSelectedFile().getAbsolutePath();
        if (!path.toLowerCase().endsWith(".csv")) path += ".csv";
        writeCSV(path, points);
        return true;
    }

    // ── Core readers ──────────────────────────────────────────────────────────

    /** Read from a file path. */
    public static List<SpatialPoint> readCSV(String filePath) throws Exception {
        try (InputStream is = new FileInputStream(filePath)) {
            return readCSV(is);
        }
    }

    /**
     * Read from any InputStream (used for classpath/bundled resources).
     * Handles quoted fields, 2-D and 3-D formats, and the school CSV layout.
     */
    public static List<SpatialPoint> readCSV(InputStream is) throws Exception {
        List<SpatialPoint> points = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));

        String headerLine = reader.readLine();
        if (headerLine == null) return points;

        // Detect school-CSV layout by header keywords
        boolean schoolLayout = headerLine.toUpperCase().contains("STUDENTS GENDER")
                            || headerLine.toUpperCase().contains("ENGLISH NAME");

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = parseCsvLine(line);
            if (parts.length < 4) continue;
            try {
                String name     = parts[0];
                double easting  = Double.parseDouble(parts[1]);
                double northing = Double.parseDouble(parts[2]);

                double elevation = 0.0;
                String category;
                String desc;

                if (schoolLayout) {
                    // School layout: col3 = STUDENTS GENDER, col4 = DISTRICT
                    category = (parts.length >= 4) ? parts[3] : "";
                    desc     = (parts.length >= 5) ? parts[4] : "";
                } else {
                    // Auto-detect 3-D: col3 is numeric → elevation
                    try {
                        elevation = Double.parseDouble(parts[3]);
                        category  = (parts.length >= 5) ? parts[4] : "";
                        desc      = (parts.length >= 6) ? parts[5] : "";
                    } catch (NumberFormatException nfe) {
                        category  = parts[3];
                        desc      = (parts.length >= 5) ? parts[4] : "";
                    }
                }
                points.add(new SpatialPoint(name, easting, northing, elevation, category, desc));
            } catch (NumberFormatException ignored) {
                // skip rows with non-numeric coordinates
            }
        }
        return points;
    }

    // ── Writer ────────────────────────────────────────────────────────────────

    public static void writeCSV(String filePath, List<SpatialPoint> points) throws Exception {
        boolean has3D = points.stream().anyMatch(p -> p.getElevation() != 0.0);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
            pw.println(has3D
                ? "NAME,EASTING,NORTHING,ELEVATION,CATEGORY,DESCRIPTION"
                : "NAME,EASTING,NORTHING,CATEGORY,DESCRIPTION");
            for (SpatialPoint p : points) {
                String name = csvQuote(p.getName());
                String cat  = csvQuote(p.getCategory());
                String desc = csvQuote(p.getDescription());
                if (has3D) {
                    pw.printf("%s,%.3f,%.3f,%.3f,%s,%s%n",
                            name, p.getEasting(), p.getNorthing(), p.getElevation(), cat, desc);
                } else {
                    pw.printf("%s,%.3f,%.3f,%s,%s%n",
                            name, p.getEasting(), p.getNorthing(), cat, desc);
                }
            }
        }
    }

    // ── Result saver ─────────────────────────────────────────────────────────

    public static boolean saveResultWithDialog(String content) throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Analysis Result");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        fc.setAcceptAllFileFilterUsed(false);
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return false;
        String path = fc.getSelectedFile().getAbsolutePath();
        if (!path.toLowerCase().endsWith(".txt")) path += ".txt";
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.print(content);
        }
        return true;
    }

    // ── RFC-4180 CSV parser ───────────────────────────────────────────────────

    /**
     * Parses one CSV line respecting double-quoted fields.
     * Handles: "field with, comma", "field ""with"" quotes", plain field
     * Results are trimmed of surrounding whitespace after unquoting.
     */
    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur   = new StringBuilder();
        boolean inQuotes    = false;
        int len = line.length();

        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < len && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++; // escaped quote: ""
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString().trim());
        return fields.toArray(new String[0]);
    }

    /** Wraps a field in quotes if it contains a comma, quote, or newline. */
    private static String csvQuote(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

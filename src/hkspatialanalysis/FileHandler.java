package hkspatialanalysis;

import java.io.*;
import java.util.*;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Handles reading and writing of spatial data files (CSV format).
 *
 * CSV format:
 *   NAME,EASTING,NORTHING,CATEGORY,DESCRIPTION
 */
public class FileHandler {

    /**
     * Opens a file-chooser dialog and reads spatial points from the selected CSV.
     * Returns null if the user cancels.
     */
    public static List<SpatialPoint> readCSVWithDialog() throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open Spatial Data File");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fc.setAcceptAllFileFilterUsed(true);
        int ret = fc.showOpenDialog(null);
        if (ret != JFileChooser.APPROVE_OPTION) return null;
        return readCSV(fc.getSelectedFile().getAbsolutePath());
    }

    /**
     * Reads spatial points from a CSV file at the given path.
     * Skips the header row and any malformed lines.
     */
    public static List<SpatialPoint> readCSV(String filePath) throws Exception {
        List<SpatialPoint> points = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        String line;
        boolean firstLine = true;
        while ((line = reader.readLine()) != null) {
            if (firstLine) { firstLine = false; continue; } // skip header
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",", 5);
            if (parts.length < 4) continue;
            try {
                String name     = parts[0].trim();
                double easting  = Double.parseDouble(parts[1].trim());
                double northing = Double.parseDouble(parts[2].trim());
                String category = parts[3].trim();
                String desc     = (parts.length >= 5) ? parts[4].trim() : "";
                points.add(new SpatialPoint(name, easting, northing, category, desc));
            } catch (NumberFormatException e) {
                // skip bad rows
            }
        }
        reader.close();
        return points;
    }

    /**
     * Saves a list of spatial points to a CSV file chosen via dialog.
     * Returns false if the user cancels.
     */
    public static boolean writeCSVWithDialog(List<SpatialPoint> points) throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Spatial Data File");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fc.setAcceptAllFileFilterUsed(false);
        int ret = fc.showSaveDialog(null);
        if (ret != JFileChooser.APPROVE_OPTION) return false;
        String path = fc.getSelectedFile().getAbsolutePath();
        if (!path.toLowerCase().endsWith(".csv")) path += ".csv";
        writeCSV(path, points);
        return true;
    }

    /**
     * Writes a list of spatial points to the given CSV path.
     */
    public static void writeCSV(String filePath, List<SpatialPoint> points) throws Exception {
        PrintWriter pw = new PrintWriter(new FileWriter(filePath));
        pw.println("NAME,EASTING,NORTHING,CATEGORY,DESCRIPTION");
        for (SpatialPoint p : points) {
            pw.printf("%s,%.3f,%.3f,%s,%s%n",
                    p.getName(), p.getEasting(), p.getNorthing(),
                    p.getCategory(), p.getDescription());
        }
        pw.close();
    }

    /**
     * Saves a plain-text analysis result to a file chosen via dialog.
     * Returns false if the user cancels.
     */
    public static boolean saveResultWithDialog(String content) throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Analysis Result");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        fc.setAcceptAllFileFilterUsed(false);
        int ret = fc.showSaveDialog(null);
        if (ret != JFileChooser.APPROVE_OPTION) return false;
        String path = fc.getSelectedFile().getAbsolutePath();
        if (!path.toLowerCase().endsWith(".txt")) path += ".txt";
        PrintWriter pw = new PrintWriter(new FileWriter(path));
        pw.print(content);
        pw.close();
        return true;
    }
}

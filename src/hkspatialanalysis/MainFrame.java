package hkspatialanalysis;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;

/**
 * Main application window for the HK Spatial Analysis System.
 *
 * Layout:
 *   North  – toolbar (file ops + mode buttons)
 *   Center – MapPanel
 *   East   – control panel (algorithm selector + params + results)
 *   South  – status bar
 *
 * Implements 12 features covering all 3 project themes:
 *   Theme 1 – File handling  : Open CSV, Save Data, Save Results
 *   Theme 2 – Spatial ops    : Join, Polar, Polygon Area, PiP, Convex Hull,
 *                              KNN, Line Intersection, Buffer, MBR, Centroid,
 *                              Pt-to-Line Distance, MST
 *   Theme 3 – Graphic display: MapPanel with colours, zoom, pan, overlays
 */
public class MainFrame extends JFrame implements PropertyChangeListener {

    private MapPanel        mapPanel;
    private JTextArea       resultArea;
    private JLabel          statusLabel;
    private JComboBox<String> algoCombo;
    private JTextField      param1Field, param2Field, param3Field;
    private JLabel          param1Label, param2Label, param3Label;
    private JButton         runButton, clearButton;
    private JLabel          selectedPointLabel;

    private List<SpatialPoint> points = new ArrayList<>();

    // Algorithm names matching combo indices
    private static final String[] ALGO_NAMES = {
        "1. Join Computation (Distance & WCB)",
        "2. Polar Computation",
        "3. Polygon Area",
        "4. Point-in-Polygon",
        "5. Convex Hull",
        "6. K-Nearest Neighbours",
        "7. Line Segment Intersection",
        "8. Buffer Zone",
        "9. Minimum Bounding Rectangle",
        "10. Centroid of Point Set",
        "11. Point-to-Line Distance",
        "12. Minimum Spanning Tree"
    };

    public MainFrame() {
        super("HK Spatial Analysis System  |  LSGI 3230A Group Project");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 750);
        setLocationRelativeTo(null);
        buildUI();
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildUI() {
        setLayout(new BorderLayout(4, 4));
        add(buildToolBar(),    BorderLayout.NORTH);
        mapPanel = new MapPanel();
        mapPanel.addPropertyChangeListener(this);
        mapPanel.setBorder(BorderFactory.createLineBorder(new Color(100,120,160), 1));
        add(mapPanel,          BorderLayout.CENTER);
        add(buildControlPanel(), BorderLayout.EAST);
        add(buildStatusBar(),  BorderLayout.SOUTH);
        buildMenuBar();
    }

    private void buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        addMenuItem(file, "Open CSV Data…",   e -> openFile());
        addMenuItem(file, "Save Data…",       e -> saveData());
        addMenuItem(file, "Save Results…",    e -> saveResults());
        file.addSeparator();
        addMenuItem(file, "Exit",             e -> System.exit(0));
        bar.add(file);

        JMenu view = new JMenu("View");
        addMenuItem(view, "Fit to Data",      e -> { mapPanel.setPoints(points); });
        addMenuItem(view, "Clear Overlays",   e -> clearOverlays());
        bar.add(view);

        JMenu help = new JMenu("Help");
        addMenuItem(help, "About", e -> JOptionPane.showMessageDialog(this,
            "HK Spatial Analysis System\n" +
            "LSGI 3230A: Geomatics Algorithms and Programming\n" +
            "Group Project – 12 Spatial Features\n\n" +
            "Features:\n" +
            "  1. Join Computation  7. Line Intersection\n" +
            "  2. Polar Computation 8. Buffer Zone\n" +
            "  3. Polygon Area      9. Minimum Bounding Rectangle\n" +
            "  4. Point-in-Polygon  10. Centroid\n" +
            "  5. Convex Hull       11. Point-to-Line Distance\n" +
            "  6. K-Nearest Nbrs   12. Minimum Spanning Tree",
            "About", JOptionPane.INFORMATION_MESSAGE));
        bar.add(help);
        setJMenuBar(bar);
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBackground(new Color(50, 70, 120));

        addToolButton(tb, "Open CSV",   new Color(80,160,80),   e -> openFile());
        addToolButton(tb, "Save Data",  new Color(80,130,200),  e -> saveData());
        addToolButton(tb, "Save Result",new Color(80,130,200),  e -> saveResults());
        tb.addSeparator();

        // Mode buttons
        addToolButton(tb, "Select/Pan", new Color(100,100,150), e -> mapPanel.setMode(MapPanel.MODE_SELECT));
        addToolButton(tb, "Draw Polygon",new Color(200,120,50), e -> {
            mapPanel.setMode(MapPanel.MODE_POLYGON);
            status("Draw polygon: click vertices, double-click to close.");
        });
        addToolButton(tb, "Set Query Pt",new Color(150,50,150), e -> {
            mapPanel.setMode(MapPanel.MODE_QUERY);
            status("Click on map to set query location.");
        });
        addToolButton(tb, "Define Lines",new Color(50,150,180), e -> {
            mapPanel.setMode(MapPanel.MODE_LINE);
            status("Click 4 points to define 2 line segments.");
        });
        tb.addSeparator();
        addToolButton(tb, "Clear All",  new Color(160,60,60),   e -> clearOverlays());
        return tb;
    }

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(290, 600));
        panel.setBackground(new Color(240, 244, 252));
        panel.setBorder(new EmptyBorder(8, 6, 8, 6));

        // Selected point info
        selectedPointLabel = new JLabel("No point selected");
        selectedPointLabel.setFont(new Font("Arial", Font.ITALIC, 11));
        selectedPointLabel.setForeground(new Color(80, 80, 100));
        panel.add(wrap("Selected Point", selectedPointLabel));
        panel.add(Box.createVerticalStrut(6));

        // Algorithm selector
        algoCombo = new JComboBox<>(ALGO_NAMES);
        algoCombo.setFont(new Font("Arial", Font.PLAIN, 11));
        algoCombo.addActionListener(e -> updateParamFields());
        panel.add(wrap("Algorithm", algoCombo));
        panel.add(Box.createVerticalStrut(4));

        // Parameter fields
        param1Label = new JLabel("Param 1");
        param1Field = new JTextField();
        param2Label = new JLabel("Param 2");
        param2Field = new JTextField();
        param3Label = new JLabel("Param 3");
        param3Field = new JTextField();
        for (JTextField f : new JTextField[]{param1Field, param2Field, param3Field}) {
            f.setFont(new Font("Arial", Font.PLAIN, 11));
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        }
        JPanel paramPanel = new JPanel();
        paramPanel.setLayout(new GridLayout(0, 2, 4, 4));
        paramPanel.setBackground(new Color(240,244,252));
        paramPanel.add(param1Label); paramPanel.add(param1Field);
        paramPanel.add(param2Label); paramPanel.add(param2Field);
        paramPanel.add(param3Label); paramPanel.add(param3Field);
        panel.add(wrapBorder("Parameters", paramPanel));
        panel.add(Box.createVerticalStrut(6));

        // Run / Clear buttons
        runButton   = makeButton("Run Algorithm", new Color(40,140,60));
        clearButton = makeButton("Clear Overlays", new Color(160,60,60));
        runButton.addActionListener(e -> runAlgorithm());
        clearButton.addActionListener(e -> clearOverlays());
        JPanel btnRow = new JPanel(new GridLayout(1,2,4,0));
        btnRow.setBackground(new Color(240,244,252));
        btnRow.add(runButton); btnRow.add(clearButton);
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        panel.add(btnRow);
        panel.add(Box.createVerticalStrut(6));

        // Results area
        resultArea = new JTextArea(18, 25);
        resultArea.setEditable(false);
        resultArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        resultArea.setBackground(new Color(250, 252, 255));
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(resultArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(160,180,220)),
                "Results", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 11), new Color(50,70,130)));
        panel.add(sp);

        updateParamFields();
        return panel;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(50, 70, 120));
        bar.setBorder(new EmptyBorder(2, 8, 2, 8));
        statusLabel = new JLabel("Ready. Open a CSV file to begin.");
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        bar.add(statusLabel, BorderLayout.WEST);
        return bar;
    }

    // -------------------------------------------------------------------------
    // Param field configuration per algorithm
    // -------------------------------------------------------------------------

    private void updateParamFields() {
        int idx = algoCombo.getSelectedIndex();
        // Reset
        param1Label.setText("—"); param1Field.setText(""); param1Field.setEnabled(false);
        param2Label.setText("—"); param2Field.setText(""); param2Field.setEnabled(false);
        param3Label.setText("—"); param3Field.setText(""); param3Field.setEnabled(false);
        switch (idx) {
            case 0: // Join: needs 2 selected points – no extra params
                param1Label.setText("(Select 2 points on map)");
                break;
            case 1: // Polar
                param1Label.setText("Bearing (deg)"); param1Field.setEnabled(true);
                param2Label.setText("Distance (m)");  param2Field.setEnabled(true);
                break;
            case 2: // Polygon area – draw polygon on map
                param1Label.setText("(Draw polygon on map)");
                break;
            case 3: // PiP – draw polygon
                param1Label.setText("(Draw polygon on map)");
                break;
            case 5: // KNN
                param1Label.setText("K (# neighbours)"); param1Field.setText("3"); param1Field.setEnabled(true);
                param2Label.setText("(Set query pt on map)");
                break;
            case 7: // Buffer
                param1Label.setText("Radius (m)"); param1Field.setText("1000"); param1Field.setEnabled(true);
                param2Label.setText("(Set query pt on map)");
                break;
            case 10: // Pt-to-Line
                param1Label.setText("(Select pt + draw polygon edge as line)");
                break;
            case 11: // MST – no params needed
                param1Label.setText("(Runs on all loaded points)");
                break;
            default:
                break;
        }
    }

    // -------------------------------------------------------------------------
    // File operations
    // -------------------------------------------------------------------------

    private void openFile() {
        try {
            List<SpatialPoint> loaded = FileHandler.readCSVWithDialog();
            if (loaded == null) return;
            if (loaded.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No valid data found in file.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }
            points = loaded;
            mapPanel.setPoints(points);
            status("Loaded " + points.size() + " points.");
            resultArea.setText("Loaded " + points.size() + " spatial points.\n");
            for (SpatialPoint p : points) resultArea.append("  " + p + "\n");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error reading file:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveData() {
        if (points.isEmpty()) { status("No data to save."); return; }
        try {
            boolean saved = FileHandler.writeCSVWithDialog(points);
            if (saved) status("Data saved.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error saving:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveResults() {
        String txt = resultArea.getText();
        if (txt.isBlank()) { status("No results to save."); return; }
        try {
            boolean saved = FileHandler.saveResultWithDialog(txt);
            if (saved) status("Results saved.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error saving results:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -------------------------------------------------------------------------
    // Algorithm dispatcher
    // -------------------------------------------------------------------------

    private void runAlgorithm() {
        if (points.isEmpty()) { status("Load data first."); return; }
        int algo = algoCombo.getSelectedIndex();
        switch (algo) {
            case 0:  runJoinComputation();       break;
            case 1:  runPolarComputation();      break;
            case 2:  runPolygonArea();            break;
            case 3:  runPointInPolygon();         break;
            case 4:  runConvexHull();             break;
            case 5:  runKNN();                    break;
            case 6:  runLineIntersection();       break;
            case 7:  runBufferZone();             break;
            case 8:  runMBR();                    break;
            case 9:  runCentroid();               break;
            case 10: runPointToLineDistance();    break;
            case 11: runMST();                    break;
        }
    }

    // ------ Feature 1: Join Computation ----------------------------------------

    private void runJoinComputation() {
        // Expects exactly 2 points selected (sequential click – use second click of pair)
        // Simpler approach: show a dialog to pick two point indices
        String[] names = points.stream().map(SpatialPoint::getName).toArray(String[]::new);
        JComboBox<String> cbA = new JComboBox<>(names);
        JComboBox<String> cbB = new JComboBox<>(names);
        if (names.length > 1) cbB.setSelectedIndex(1);
        Object[] msg = {"Point A:", cbA, "Point B:", cbB};
        int opt = JOptionPane.showConfirmDialog(this, msg, "Join Computation", JOptionPane.OK_CANCEL_OPTION);
        if (opt != JOptionPane.OK_OPTION) return;

        int iA = cbA.getSelectedIndex(), iB = cbB.getSelectedIndex();
        SpatialPoint A = points.get(iA), B = points.get(iB);
        double dist = SpatialAlgorithms.distance(A.getEasting(), A.getNorthing(),
                                                  B.getEasting(), B.getNorthing());
        double wcb  = SpatialAlgorithms.wholeCircleBearing(A.getEasting(), A.getNorthing(),
                                                             B.getEasting(), B.getNorthing());
        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 1: Join Computation ===\n");
        sb.append(String.format("From: %s  (E=%.3f, N=%.3f)%n", A.getName(), A.getEasting(), A.getNorthing()));
        sb.append(String.format("To:   %s  (E=%.3f, N=%.3f)%n", B.getName(), B.getEasting(), B.getNorthing()));
        sb.append(String.format("Distance : %.3f m%n", dist));
        sb.append(String.format("WCB      : %.6f\u00b0  (%s)%n", wcb, SpatialAlgorithms.toDDMMSS(wcb)));

        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(Arrays.asList(iA, iB));
        status(String.format("Join: dist=%.2f m, WCB=%.4f\u00b0", dist, wcb));
    }

    // ------ Feature 2: Polar Computation ---------------------------------------

    private void runPolarComputation() {
        int sel = mapPanel.getSelectedIndex();
        if (sel < 0) { status("Click to select an origin point first."); return; }
        double bearing, dist;
        try {
            bearing = Double.parseDouble(param1Field.getText().trim());
            dist    = Double.parseDouble(param2Field.getText().trim());
        } catch (NumberFormatException ex) { status("Enter valid bearing (deg) and distance (m)."); return; }

        SpatialPoint origin = points.get(sel);
        double[] result = SpatialAlgorithms.polarComputation(origin.getEasting(), origin.getNorthing(), bearing, dist);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 2: Polar Computation ===\n");
        sb.append(String.format("Origin : %s  (E=%.3f, N=%.3f)%n", origin.getName(), origin.getEasting(), origin.getNorthing()));
        sb.append(String.format("Bearing: %.4f\u00b0%n", bearing));
        sb.append(String.format("Distance: %.3f m%n", dist));
        sb.append(String.format("New Point: E=%.3f, N=%.3f%n", result[0], result[1]));

        resultArea.setText(sb.toString());
        mapPanel.setPolarResult(result);
        status(String.format("Polar result: E=%.1f, N=%.1f", result[0], result[1]));
    }

    // ------ Feature 3: Polygon Area --------------------------------------------

    private void runPolygonArea() {
        List<double[]> poly = mapPanel.getUserPolygon();
        if (!mapPanel.isPolygonClosed() || poly.size() < 3) {
            status("Draw and close a polygon on the map first."); return;
        }
        double[] ex = new double[poly.size()], ny = new double[poly.size()];
        for (int i = 0; i < poly.size(); i++) { ex[i] = poly.get(i)[0]; ny[i] = poly.get(i)[1]; }
        double area = SpatialAlgorithms.polygonArea(ex, ny);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 3: Polygon Area ===\n");
        sb.append(String.format("Vertices : %d%n", poly.size()));
        for (int i = 0; i < poly.size(); i++)
            sb.append(String.format("  V%d: E=%.1f, N=%.1f%n", i+1, ex[i], ny[i]));
        sb.append(String.format("Area     : %.3f m\u00b2%n", area));
        sb.append(String.format("Area     : %.6f km\u00b2%n", area / 1e6));

        resultArea.setText(sb.toString());
        status(String.format("Polygon area = %.2f m²", area));
    }

    // ------ Feature 4: Point-in-Polygon ----------------------------------------

    private void runPointInPolygon() {
        List<double[]> poly = mapPanel.getUserPolygon();
        if (!mapPanel.isPolygonClosed() || poly.size() < 3) {
            status("Draw and close a polygon on the map first."); return;
        }
        double[] ex = new double[poly.size()], ny = new double[poly.size()];
        for (int i = 0; i < poly.size(); i++) { ex[i] = poly.get(i)[0]; ny[i] = poly.get(i)[1]; }

        List<Integer> inside = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (SpatialAlgorithms.pointInPolygon(points.get(i).getEasting(), points.get(i).getNorthing(), ex, ny))
                inside.add(i);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 4: Point-in-Polygon ===\n");
        sb.append(String.format("Polygon vertices: %d%n", poly.size()));
        sb.append(String.format("Points inside   : %d / %d%n%n", inside.size(), points.size()));
        for (int idx : inside) sb.append("  + " + points.get(idx) + "\n");

        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(inside);
        status("Points inside polygon: " + inside.size());
    }

    // ------ Feature 5: Convex Hull ---------------------------------------------

    private void runConvexHull() {
        if (points.size() < 3) { status("Need at least 3 points."); return; }
        double[] ex = new double[points.size()], ny = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            ex[i] = points.get(i).getEasting(); ny[i] = points.get(i).getNorthing();
        }
        List<Integer> hull = SpatialAlgorithms.convexHull(ex, ny);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 5: Convex Hull (Graham Scan) ===\n");
        sb.append(String.format("Total points : %d%n", points.size()));
        sb.append(String.format("Hull vertices: %d%n%n", hull.size()));
        for (int idx : hull) sb.append("  " + points.get(idx) + "\n");
        // Compute hull area
        double[] hEx = new double[hull.size()], hNy = new double[hull.size()];
        for (int k = 0; k < hull.size(); k++) {
            hEx[k] = points.get(hull.get(k)).getEasting();
            hNy[k] = points.get(hull.get(k)).getNorthing();
        }
        sb.append(String.format("%nHull area    : %.3f m\u00b2%n", SpatialAlgorithms.polygonArea(hEx, hNy)));

        resultArea.setText(sb.toString());
        mapPanel.setHullIndices(hull);
        mapPanel.setHighlighted(new HashSet<>(hull));
        status("Convex hull: " + hull.size() + " vertices.");
    }

    // ------ Feature 6: K-Nearest Neighbours ------------------------------------

    private void runKNN() {
        if (mapPanel.getMode() != MapPanel.MODE_QUERY) {
            status("Switch to 'Set Query Pt' mode and click the map first."); return;
        }
        double[] qp = getQueryPoint();
        if (qp == null) { status("Click on map to set query location."); return; }
        int k;
        try { k = Integer.parseInt(param1Field.getText().trim()); }
        catch (NumberFormatException ex) { k = 3; }
        k = Math.max(1, Math.min(k, points.size()));

        List<Integer> knn = SpatialAlgorithms.kNearestNeighbours(points, qp[0], qp[1], k);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 6: K-Nearest Neighbours ===\n");
        sb.append(String.format("Query  : E=%.3f, N=%.3f%n", qp[0], qp[1]));
        sb.append(String.format("K      : %d%n%n", k));
        for (int rank = 0; rank < knn.size(); rank++) {
            int idx = knn.get(rank);
            double d = SpatialAlgorithms.distance(qp[0], qp[1],
                    points.get(idx).getEasting(), points.get(idx).getNorthing());
            sb.append(String.format("  #%d  %s  (%.1f m)%n", rank+1, points.get(idx).getName(), d));
        }
        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(new HashSet<>(knn));
        status("KNN found " + knn.size() + " nearest neighbours.");
    }

    // ------ Feature 7: Line Segment Intersection --------------------------------

    private void runLineIntersection() {
        List<double[]> lp = mapPanel.getLinePoints();
        if (lp.size() < 4) { status("Define 4 points (2 line segments) in 'Define Lines' mode."); return; }
        double[] A = lp.get(0), B = lp.get(1), C = lp.get(2), D = lp.get(3);
        double[] ipt = SpatialAlgorithms.lineSegmentIntersection(
                A[0],A[1], B[0],B[1], C[0],C[1], D[0],D[1]);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 7: Line Segment Intersection ===\n");
        sb.append(String.format("Segment AB: (%.1f,%.1f) -> (%.1f,%.1f)%n", A[0],A[1],B[0],B[1]));
        sb.append(String.format("Segment CD: (%.1f,%.1f) -> (%.1f,%.1f)%n", C[0],C[1],D[0],D[1]));
        if (ipt != null) {
            sb.append(String.format("%nIntersects at: E=%.3f, N=%.3f%n", ipt[0], ipt[1]));
            mapPanel.setIntersectPoint(ipt);
        } else {
            sb.append("\nSegments do NOT intersect.\n");
            mapPanel.setIntersectPoint(null);
        }
        resultArea.setText(sb.toString());
        status(ipt != null ? "Intersection found." : "No intersection.");
    }

    // ------ Feature 8: Buffer Zone ----------------------------------------------

    private void runBufferZone() {
        double[] qp = getQueryPoint();
        if (qp == null) { status("Set query point in 'Set Query Pt' mode."); return; }
        double radius;
        try { radius = Double.parseDouble(param1Field.getText().trim()); }
        catch (NumberFormatException ex) { status("Enter a valid radius (m)."); return; }

        List<Integer> within = SpatialAlgorithms.bufferZone(points, qp[0], qp[1], radius);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 8: Buffer Zone ===\n");
        sb.append(String.format("Centre : E=%.3f, N=%.3f%n", qp[0], qp[1]));
        sb.append(String.format("Radius : %.1f m%n%n", radius));
        sb.append(String.format("Points within buffer: %d%n%n", within.size()));
        for (int idx : within) {
            double d = SpatialAlgorithms.distance(qp[0], qp[1],
                    points.get(idx).getEasting(), points.get(idx).getNorthing());
            sb.append(String.format("  %s  (%.1f m)%n", points.get(idx).getName(), d));
        }
        resultArea.setText(sb.toString());
        mapPanel.setQueryPoint(qp[0], qp[1], radius);
        mapPanel.setHighlighted(new HashSet<>(within));
        status("Buffer zone: " + within.size() + " points within " + (int)radius + " m.");
    }

    // ------ Feature 9: Minimum Bounding Rectangle --------------------------------

    private void runMBR() {
        double[] mbr = SpatialAlgorithms.minimumBoundingRectangle(points);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 9: Minimum Bounding Rectangle ===\n");
        sb.append(String.format("Min Easting : %.3f%n", mbr[0]));
        sb.append(String.format("Min Northing: %.3f%n", mbr[1]));
        sb.append(String.format("Max Easting : %.3f%n", mbr[2]));
        sb.append(String.format("Max Northing: %.3f%n", mbr[3]));
        sb.append(String.format("Width (E)   : %.3f m%n", mbr[2]-mbr[0]));
        sb.append(String.format("Height (N)  : %.3f m%n", mbr[3]-mbr[1]));
        sb.append(String.format("MBR Area    : %.3f m\u00b2%n", (mbr[2]-mbr[0])*(mbr[3]-mbr[1])));

        resultArea.setText(sb.toString());
        mapPanel.setMBR(mbr);
        status("MBR computed.");
    }

    // ------ Feature 10: Centroid ------------------------------------------------

    private void runCentroid() {
        double[] c = SpatialAlgorithms.centroid(points);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 10: Centroid of Point Set ===\n");
        sb.append(String.format("Number of points: %d%n", points.size()));
        sb.append(String.format("Centroid E      : %.3f%n", c[0]));
        sb.append(String.format("Centroid N      : %.3f%n", c[1]));

        // Find nearest point to centroid
        List<Integer> nearest = SpatialAlgorithms.kNearestNeighbours(points, c[0], c[1], 1);
        if (!nearest.isEmpty()) {
            SpatialPoint np = points.get(nearest.get(0));
            sb.append(String.format("%nNearest point to centroid: %s (%.1f m)%n",
                np.getName(),
                SpatialAlgorithms.distance(c[0],c[1],np.getEasting(),np.getNorthing())));
        }
        resultArea.setText(sb.toString());
        mapPanel.setCentroid(c);
        status("Centroid: E=" + String.format("%.1f", c[0]) + ", N=" + String.format("%.1f", c[1]));
    }

    // ------ Feature 11: Point-to-Line Distance ----------------------------------

    private void runPointToLineDistance() {
        int sel = mapPanel.getSelectedIndex();
        if (sel < 0) { status("Select a point first (click in Select mode)."); return; }
        List<double[]> poly = mapPanel.getUserPolygon();
        if (poly.size() < 2) { status("Draw at least 2 polygon vertices to define a line."); return; }

        SpatialPoint P = points.get(sel);
        double[] A = poly.get(0), B = poly.get(1);
        double d = SpatialAlgorithms.pointToLineDistance(
                P.getEasting(), P.getNorthing(), A[0], A[1], B[0], B[1]);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 11: Point-to-Line Distance ===\n");
        sb.append(String.format("Point  : %s (E=%.3f, N=%.3f)%n", P.getName(), P.getEasting(), P.getNorthing()));
        sb.append(String.format("Line A : E=%.3f, N=%.3f%n", A[0], A[1]));
        sb.append(String.format("Line B : E=%.3f, N=%.3f%n", B[0], B[1]));
        sb.append(String.format("Perpendicular distance: %.3f m%n", d));

        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(Collections.singletonList(sel));
        status(String.format("Point-to-line dist = %.2f m", d));
    }

    // ------ Feature 12: Minimum Spanning Tree -----------------------------------

    private void runMST() {
        if (points.size() < 2) { status("Need at least 2 points."); return; }
        List<int[]> edges = SpatialAlgorithms.minimumSpanningTree(points);

        double totalLength = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 12: Minimum Spanning Tree (Prim) ===\n");
        sb.append(String.format("Points  : %d%n", points.size()));
        sb.append(String.format("Edges   : %d%n%n", edges.size()));
        for (int[] e : edges) {
            double len = points.get(e[0]).distanceTo(points.get(e[1]));
            totalLength += len;
            sb.append(String.format("  %s -- %s  (%.1f m)%n",
                    points.get(e[0]).getName(), points.get(e[1]).getName(), len));
        }
        sb.append(String.format("%nTotal MST length: %.3f m%n", totalLength));

        resultArea.setText(sb.toString());
        mapPanel.setMSTEdges(edges);
        status(String.format("MST: %d edges, total=%.1f m", edges.size(), totalLength));
    }

    // -------------------------------------------------------------------------
    // PropertyChangeListener – map events
    // -------------------------------------------------------------------------

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        switch (evt.getPropertyName()) {
            case "selectedPoint":
                int idx = (int) evt.getNewValue();
                if (idx >= 0 && idx < points.size()) {
                    SpatialPoint p = points.get(idx);
                    selectedPointLabel.setText("<html><b>" + p.getName() + "</b><br/>" +
                            p.getCategory() + "<br/>E=" + String.format("%.1f", p.getEasting()) +
                            " N=" + String.format("%.1f", p.getNorthing()) + "</html>");
                    status("Selected: " + p.getName());
                } else {
                    selectedPointLabel.setText("No point selected");
                }
                break;
            case "polygonClosed":
                status("Polygon closed (" + mapPanel.getUserPolygon().size() + " vertices). Run an algorithm.");
                break;
            case "queryPoint":
                double[] qp = (double[]) evt.getNewValue();
                cachedQueryPoint = qp;
                status(String.format("Query point set: E=%.1f, N=%.1f", qp[0], qp[1]));
                break;
            case "lineDefined":
                status("2 line segments defined. Run 'Line Segment Intersection'.");
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double[] getQueryPoint() {
        // Retrieve from map panel via reflection of internal field – instead store separately
        // Use a side-channel: we listen to the property event and cache it
        return cachedQueryPoint;
    }

    private double[] cachedQueryPoint = null;

    // Override propertyChange to also cache query point
    { } // initialiser block – override done by re-registering (handled in constructor)

    private void clearOverlays() {
        mapPanel.clearOverlays();
        resultArea.setText("");
        selectedPointLabel.setText("No point selected");
        status("Overlays cleared.");
    }

    private void status(String msg) {
        statusLabel.setText(msg);
    }

    private void addMenuItem(JMenu menu, String label, ActionListener al) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(al);
        menu.add(item);
    }

    private void addToolButton(JToolBar tb, String label, Color bg, ActionListener al) {
        JButton btn = new JButton(label);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setFont(new Font("Arial", Font.BOLD, 11));
        btn.addActionListener(al);
        tb.add(btn);
        tb.addSeparator(new Dimension(3, 0));
    }

    private JButton makeButton(String label, Color bg) {
        JButton b = new JButton(label);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(new Font("Arial", Font.BOLD, 11));
        return b;
    }

    private JPanel wrap(String title, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout(2,2));
        p.setBackground(new Color(240,244,252));
        p.setBorder(new EmptyBorder(2,0,2,0));
        JLabel lbl = new JLabel(title + ":");
        lbl.setFont(new Font("Arial", Font.BOLD, 11));
        lbl.setForeground(new Color(50,70,130));
        p.add(lbl, BorderLayout.NORTH);
        p.add(comp, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height + 22));
        return p;
    }

    private JPanel wrapBorder(String title, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(240,244,252));
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(160,180,220)),
                title, TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 11), new Color(50,70,130)));
        p.add(comp, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        return p;
    }

    // Override addPropertyChangeListener to intercept query-point events
    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        super.addPropertyChangeListener(listener);
    }
}

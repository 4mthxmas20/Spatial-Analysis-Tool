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
 * Implements 14 features covering all 3 project themes:
 *   Theme 1 – File handling  : Open CSV, Open Shapefile (GeoTools), Save Data, Save Results
 *   Theme 2 – Spatial ops    : Join, Polar, Polygon Area, PiP, Convex Hull,
 *                              KNN, Line Intersection, Buffer, MBR, Centroid,
 *                              Pt-to-Line Distance, MST,
 *                              CRS Transform (GeoTools), Attribute Filter (GeoTools)
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
        "12. Minimum Spanning Tree",
        "13. CRS Transform (GeoTools)",
        "14. Shapefile Attribute Filter (GeoTools)",
        "── 3-D Algorithms ──────────────",
        "15. 3D Distance, Slope & Vertical Angle",
        "16. 3D K-Nearest Neighbours",
        "17. 3D Spherical Buffer Zone",
        "18. 3D Bounding Box & Volume",
        "19. IDW Elevation Interpolation"
    };

    public MainFrame() {
        super("HK Spatial Analysis System");
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
        // ── Auto-load bundled school dataset ──────────────────────────────
        SwingUtilities.invokeLater(this::loadBundledSchoolData);
        add(buildStatusBar(),  BorderLayout.SOUTH);
        buildMenuBar();
    }

    private void buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        addMenuItem(file, "Open CSV Data…",      e -> openFile());
        addMenuItem(file, "Open Shapefile (GeoTools)…", e -> openShapefile());
        addMenuItem(file, "Save Data…",          e -> saveData());
        addMenuItem(file, "Save Results…",       e -> saveResults());
        file.addSeparator();
        addMenuItem(file, "Exit",                e -> System.exit(0));
        bar.add(file);

        JMenu view = new JMenu("View");
        addMenuItem(view, "Fit to Data",      e -> { mapPanel.setPoints(points); });
        addMenuItem(view, "Clear Overlays",   e -> clearOverlays());
        bar.add(view);

        JMenu help = new JMenu("Help");
        addMenuItem(help, "About", e -> JOptionPane.showMessageDialog(this,
            "HK Spatial Analysis System\n" +
            "14 Spatial Features (incl. GeoTools)\n\n" +
            "Features:\n" +
            "  1.  Join Computation       8.  Buffer Zone\n" +
            "  2.  Polar Computation      9.  Min. Bounding Rectangle\n" +
            "  3.  Polygon Area           10. Centroid\n" +
            "  4.  Point-in-Polygon       11. Point-to-Line Distance\n" +
            "  5.  Convex Hull            12. Min. Spanning Tree\n" +
            "  6.  K-Nearest Neighbours   13. CRS Transform (GeoTools)\n" +
            "  7.  Line Intersection      14. Shapefile Attr Filter (GeoTools)\n\n" +
            "Libraries: Java Swing, GeoTools " + getGeoToolsVersion(),
            "About", JOptionPane.INFORMATION_MESSAGE));
        bar.add(help);
        setJMenuBar(bar);
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBackground(new Color(50, 70, 120));

        addToolButton(tb, "Open CSV",      new Color(80,160,80),   e -> openFile());
        addToolButton(tb, "Open SHP",      new Color(60,130,60),   e -> openShapefile());
        addToolButton(tb, "Save Data",     new Color(80,130,200),  e -> saveData());
        addToolButton(tb, "Save Result",   new Color(80,130,200),  e -> saveResults());
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
        tb.addSeparator();

        // ── 3D View ─────────────────────────────────────────────────────────
        addToolButton(tb, "3D View (CesiumJS)", new Color(30, 80, 160), e -> open3DViewer());
        tb.addSeparator();

        // ── Basemap toggle ──────────────────────────────────────────────────
        JToggleButton basemapBtn = new JToggleButton("Basemap ON");
        basemapBtn.setSelected(true);
        basemapBtn.setBackground(new Color(30,120,100));
        basemapBtn.setForeground(Color.WHITE);
        basemapBtn.setFocusPainted(false);
        basemapBtn.setFont(new Font("Arial", Font.BOLD, 11));
        basemapBtn.addActionListener(e -> {
            boolean on = basemapBtn.isSelected();
            mapPanel.setShowBasemap(on);
            basemapBtn.setText(on ? "Basemap ON" : "Basemap OFF");
            basemapBtn.setBackground(on ? new Color(30,120,100) : new Color(100,100,100));
            status(on ? "Basemap enabled." : "Basemap disabled.");
        });
        tb.add(basemapBtn);
        tb.addSeparator(new Dimension(3,0));

        // ── Tile server selector ────────────────────────────────────────────
        JLabel serverLabel = new JLabel("  Map: ");
        serverLabel.setForeground(Color.WHITE);
        serverLabel.setFont(new Font("Arial", Font.BOLD, 11));
        tb.add(serverLabel);

        TileMapProvider.TileServer[] servers = TileMapProvider.TileServer.values();
        JComboBox<TileMapProvider.TileServer> serverCombo = new JComboBox<>(servers);
        serverCombo.setSelectedItem(TileMapProvider.TileServer.CARTO_LIGHT);
        serverCombo.setMaximumSize(new Dimension(220, 26));
        serverCombo.setFont(new Font("Arial", Font.PLAIN, 11));
        serverCombo.setToolTipText("Switch tile basemap provider (no API key required)");
        serverCombo.addActionListener(e -> {
            TileMapProvider.TileServer sel =
                (TileMapProvider.TileServer) serverCombo.getSelectedItem();
            if (sel != null) {
                mapPanel.setTileServer(sel);
                status("Tile server: " + sel.displayName);
            }
        });
        tb.add(serverCombo);
        tb.addSeparator(new Dimension(6, 0));

        // ── Color-by toggle ─────────────────────────────────────────────────
        JToggleButton colorByBtn = new JToggleButton("🎨 Color: Gender");
        colorByBtn.setSelected(false);
        colorByBtn.setBackground(new Color(98, 72, 224));   // CO-ED purple
        colorByBtn.setForeground(Color.WHITE);
        colorByBtn.setFocusPainted(false);
        colorByBtn.setFont(new Font("Arial", Font.BOLD, 11));
        colorByBtn.setToolTipText("Toggle point colouring between Gender and District");
        colorByBtn.addActionListener(e -> {
            boolean byDist = colorByBtn.isSelected();
            mapPanel.setColorByDesc(byDist);
            if (byDist) {
                colorByBtn.setText("🗺 Color: District");
                colorByBtn.setBackground(new Color(30, 120, 200));
                status("Points coloured by District.");
            } else {
                colorByBtn.setText("🎨 Color: Gender");
                colorByBtn.setBackground(new Color(98, 72, 224));
                status("Points coloured by Gender (CO-ED / BOYS / GIRLS).");
            }
        });
        tb.add(colorByBtn);

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
            case 12: // CRS Transform
                param1Label.setText("Source EPSG"); param1Field.setText("EPSG:2326"); param1Field.setEnabled(true);
                param2Label.setText("Target EPSG"); param2Field.setText("EPSG:4326"); param2Field.setEnabled(true);
                param3Label.setText("(Transforms all loaded points)");
                break;
            case 13: // Attribute filter
                param1Label.setText("Attribute name"); param1Field.setText("CATEGORY"); param1Field.setEnabled(true);
                param2Label.setText("Filter value");   param2Field.setText("");         param2Field.setEnabled(true);
                param3Label.setText("(Filter loaded points)");
                break;
            // ── 3-D algorithms ──────────────────────────────────────────────
            case 15: // 3D Distance
                param1Label.setText("(Select 2 points on map)");
                param2Label.setText("Z scale factor"); param2Field.setText("1.0"); param2Field.setEnabled(true);
                break;
            case 16: // 3D KNN
                param1Label.setText("K (# neighbours)"); param1Field.setText("3"); param1Field.setEnabled(true);
                param2Label.setText("Query Z (m)");      param2Field.setText("0"); param2Field.setEnabled(true);
                param3Label.setText("Z scale factor");   param3Field.setText("1"); param3Field.setEnabled(true);
                break;
            case 17: // 3D Spherical Buffer
                param1Label.setText("Radius (m)");  param1Field.setText("1000"); param1Field.setEnabled(true);
                param2Label.setText("Centre Z (m)");param2Field.setText("0");    param2Field.setEnabled(true);
                param3Label.setText("(Set query pt on map)");
                break;
            case 18: // 3D Bounding Box
                param1Label.setText("(Runs on all loaded points)");
                break;
            case 19: // IDW Interpolation
                param1Label.setText("Query E (m)");  param1Field.setEnabled(true); param1Field.setText("");
                param2Label.setText("Query N (m)");  param2Field.setEnabled(true); param2Field.setText("");
                param3Label.setText("IDW power p");  param3Field.setEnabled(true); param3Field.setText("2");
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
            case 12: runCRSTransform();           break;
            case 13: runAttributeFilter();        break;
            case 14: /* separator – no-op */      break;
            case 15: run3DDistance();             break;
            case 16: run3DKNN();                  break;
            case 17: run3DBuffer();               break;
            case 18: run3DBoundingBox();          break;
            case 19: runIDWInterpolation();       break;
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

    // ------ Feature 13: CRS Transformation (GeoTools) --------------------------

    private void runCRSTransform() {
        String fromCode = param1Field.getText().trim();
        String toCode   = param2Field.getText().trim();
        if (fromCode.isEmpty()) fromCode = CRSTransformer.EPSG_HK1980;
        if (toCode.isEmpty())   toCode   = CRSTransformer.EPSG_WGS84;

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 13: CRS Transformation (GeoTools) ===\n");
        sb.append("Source CRS : ").append(fromCode).append("\n");
        sb.append("Target CRS : ").append(toCode).append("\n\n");
        sb.append(String.format("%-30s  %14s  %14s%n", "Name", "Target X", "Target Y"));
        sb.append("-".repeat(62)).append("\n");

        int errors = 0;
        for (SpatialPoint p : points) {
            try {
                double[] out = CRSTransformer.transform(fromCode, toCode,
                        p.getEasting(), p.getNorthing());
                // If converting to WGS84 (lat/lon), format as DMS
                if (toCode.toUpperCase().contains("4326")) {
                    sb.append(String.format("%-30s  %s  %s%n",
                            shorten(p.getName(), 30),
                            CRSTransformer.formatDMS(out[1], true),   // lat
                            CRSTransformer.formatDMS(out[0], false))); // lon
                } else {
                    sb.append(String.format("%-30s  %14.3f  %14.3f%n",
                            shorten(p.getName(), 30), out[0], out[1]));
                }
            } catch (Exception e) {
                sb.append(String.format("%-30s  ERROR: %s%n", shorten(p.getName(), 30), e.getMessage()));
                errors++;
            }
        }
        if (errors > 0) sb.append("\n").append(errors).append(" point(s) failed to transform.\n");

        resultArea.setText(sb.toString());
        status("CRS transform done: " + fromCode + " → " + toCode);
    }

    // ------ Feature 14: Shapefile Attribute Filter (GeoTools) ------------------

    private void runAttributeFilter() {
        String attrName  = param1Field.getText().trim();
        String filterVal = param2Field.getText().trim();
        if (attrName.isEmpty()) { status("Enter an attribute name to filter on."); return; }

        // Filter on SpatialPoint fields mapped from shapefile/CSV attributes
        // Supported fields: NAME, CATEGORY, DESCRIPTION
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            SpatialPoint p = points.get(i);
            String fieldVal = "";
            switch (attrName.toUpperCase()) {
                case "NAME":        fieldVal = p.getName();        break;
                case "CATEGORY":    fieldVal = p.getCategory();    break;
                case "DESCRIPTION": fieldVal = p.getDescription(); break;
                default:            fieldVal = p.toString();       break;
            }
            boolean match = filterVal.isEmpty() ||
                            fieldVal.toLowerCase().contains(filterVal.toLowerCase());
            if (match) matched.add(i);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature 14: Attribute Filter (GeoTools Shapefile) ===\n");
        sb.append("Filter: ").append(attrName).append(" CONTAINS \"").append(filterVal).append("\"\n");
        sb.append(String.format("Matched: %d / %d points%n%n", matched.size(), points.size()));
        for (int idx : matched) sb.append("  " + points.get(idx) + "\n");

        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(new HashSet<>(matched));
        status("Attribute filter: " + matched.size() + " matches for " + attrName + "=\"" + filterVal + "\"");
    }

    // ── Feature 15: 3D Distance, Slope & Vertical Angle ─────────────────────────

    private void run3DDistance() {
        String[] names = points.stream().map(SpatialPoint::getName).toArray(String[]::new);
        JComboBox<String> cbA = new JComboBox<>(names);
        JComboBox<String> cbB = new JComboBox<>(names);
        if (names.length > 1) cbB.setSelectedIndex(1);
        Object[] msg = {"Point A:", cbA, "Point B:", cbB};
        int opt = JOptionPane.showConfirmDialog(this, msg, "3D Distance", JOptionPane.OK_CANCEL_OPTION);
        if (opt != JOptionPane.OK_OPTION) return;
        int iA = cbA.getSelectedIndex(), iB = cbB.getSelectedIndex();
        SpatialPoint A = points.get(iA), B = points.get(iB);

        double dist3d = SpatialAlgorithms3D.distance3D(
                A.getEasting(), A.getNorthing(), A.getElevation(),
                B.getEasting(), B.getNorthing(), B.getElevation());
        double distH  = SpatialAlgorithms3D.distanceHorizontal(
                A.getEasting(), A.getNorthing(), B.getEasting(), B.getNorthing());
        double slope  = SpatialAlgorithms3D.slopeGradient(
                A.getEasting(), A.getNorthing(), A.getElevation(),
                B.getEasting(), B.getNorthing(), B.getElevation());
        double vAngle = SpatialAlgorithms3D.verticalAngleDeg(
                A.getEasting(), A.getNorthing(), A.getElevation(),
                B.getEasting(), B.getNorthing(), B.getElevation());

        StringBuilder sb = new StringBuilder("=== Feature 15: 3D Distance, Slope & Vertical Angle ===\n");
        sb.append(String.format("From : %s  (E=%.1f, N=%.1f, Z=%.1f m)%n",
                A.getName(), A.getEasting(), A.getNorthing(), A.getElevation()));
        sb.append(String.format("To   : %s  (E=%.1f, N=%.1f, Z=%.1f m)%n",
                B.getName(), B.getEasting(), B.getNorthing(), B.getElevation()));
        sb.append(String.format("%n3D Distance        : %.3f m%n", dist3d));
        sb.append(String.format("Horizontal Distance: %.3f m%n", distH));
        sb.append(String.format("Elevation Diff     : %.3f m%n", B.getElevation()-A.getElevation()));
        sb.append(String.format("Slope Gradient     : %.4f  (%.2f %%)%n", slope, slope*100));
        sb.append(String.format("Vertical Angle     : %.4f°%n", vAngle));
        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(Arrays.asList(iA, iB));
        status(String.format("3D dist=%.2f m  slope=%.2f%%  vAngle=%.2f°", dist3d, slope*100, vAngle));
    }

    // ── Feature 16: 3D K-Nearest Neighbours ─────────────────────────────────────

    private void run3DKNN() {
        double[] qp = getQueryPoint();
        if (qp == null) { status("Set query point in 'Set Query Pt' mode first."); return; }
        int k; double qZ, zScale;
        try { k      = Math.max(1, Integer.parseInt(param1Field.getText().trim())); } catch(Exception e) { k = 3; }
        try { qZ     = Double.parseDouble(param2Field.getText().trim()); }             catch(Exception e) { qZ = 0; }
        try { zScale = Double.parseDouble(param3Field.getText().trim()); }             catch(Exception e) { zScale = 1; }
        k = Math.min(k, points.size());

        List<Integer> knn = SpatialAlgorithms3D.kNearestNeighbours3D(points, qp[0], qp[1], qZ, k, zScale);

        StringBuilder sb = new StringBuilder("=== Feature 16: 3D K-Nearest Neighbours ===\n");
        sb.append(String.format("Query  : E=%.1f, N=%.1f, Z=%.1f m%n", qp[0], qp[1], qZ));
        sb.append(String.format("K      : %d  (Z-scale=%.1f)%n%n", k, zScale));
        for (int rank = 0; rank < knn.size(); rank++) {
            SpatialPoint p = points.get(knn.get(rank));
            double d3 = SpatialAlgorithms3D.distance3D(qp[0], qp[1], qZ,
                    p.getEasting(), p.getNorthing(), p.getElevation());
            sb.append(String.format("  #%d  %-30s  %.1f m (3D)%n", rank+1, p.getName(), d3));
        }
        resultArea.setText(sb.toString());
        mapPanel.setHighlighted(new HashSet<>(knn));
        status("3D KNN: found " + knn.size() + " neighbours.");
    }

    // ── Feature 17: 3D Spherical Buffer Zone ────────────────────────────────────

    private void run3DBuffer() {
        double[] qp = getQueryPoint();
        if (qp == null) { status("Set query point in 'Set Query Pt' mode first."); return; }
        double radius, cZ;
        try { radius = Double.parseDouble(param1Field.getText().trim()); } catch(Exception e) { status("Enter radius (m)."); return; }
        try { cZ     = Double.parseDouble(param2Field.getText().trim()); } catch(Exception e) { cZ = 0; }

        List<Integer> within = SpatialAlgorithms3D.bufferZoneSphere(points, qp[0], qp[1], cZ, radius);

        StringBuilder sb = new StringBuilder("=== Feature 17: 3D Spherical Buffer Zone ===\n");
        sb.append(String.format("Centre : E=%.1f, N=%.1f, Z=%.1f m%n", qp[0], qp[1], cZ));
        sb.append(String.format("Radius : %.1f m (sphere)%n%n", radius));
        sb.append(String.format("Points within sphere: %d%n%n", within.size()));
        for (int idx : within) {
            SpatialPoint p = points.get(idx);
            double d3 = SpatialAlgorithms3D.distance3D(qp[0], qp[1], cZ,
                    p.getEasting(), p.getNorthing(), p.getElevation());
            sb.append(String.format("  %-30s  %.1f m%n", p.getName(), d3));
        }
        resultArea.setText(sb.toString());
        mapPanel.setQueryPoint(qp[0], qp[1], radius);
        mapPanel.setHighlighted(new HashSet<>(within));
        status("3D sphere buffer: " + within.size() + " points within " + (int)radius + " m.");
    }

    // ── Feature 18: 3D Bounding Box & Volume ────────────────────────────────────

    private void run3DBoundingBox() {
        double[] bb = SpatialAlgorithms3D.boundingBox3D(points);
        double vol  = SpatialAlgorithms3D.boundingBoxVolume(bb);
        double sa   = SpatialAlgorithms3D.boundingBoxSurfaceArea(bb);

        StringBuilder sb = new StringBuilder("=== Feature 18: 3D Bounding Box (AABB) ===\n");
        sb.append(String.format("Points          : %d%n%n", points.size()));
        sb.append(String.format("Min Easting     : %.3f m%n", bb[0]));
        sb.append(String.format("Min Northing    : %.3f m%n", bb[1]));
        sb.append(String.format("Min Elevation   : %.3f m%n", bb[2]));
        sb.append(String.format("Max Easting     : %.3f m%n", bb[3]));
        sb.append(String.format("Max Northing    : %.3f m%n", bb[4]));
        sb.append(String.format("Max Elevation   : %.3f m%n", bb[5]));
        sb.append(String.format("%nDimensions (W × D × H):%n"));
        sb.append(String.format("  Width  (E) : %.3f m%n", bb[3]-bb[0]));
        sb.append(String.format("  Depth  (N) : %.3f m%n", bb[4]-bb[1]));
        sb.append(String.format("  Height (Z) : %.3f m%n", bb[5]-bb[2]));
        sb.append(String.format("%nVolume       : %.3f m³%n", vol));
        sb.append(String.format("Surface Area : %.3f m²%n", sa));
        resultArea.setText(sb.toString());
        mapPanel.setMBR(new double[]{bb[0], bb[1], bb[3], bb[4]});
        status(String.format("3D AABB: W=%.1f N=%.1f H=%.1f m; Vol=%.0f m³",
                bb[3]-bb[0], bb[4]-bb[1], bb[5]-bb[2], vol));
    }

    // ── Feature 19: IDW Elevation Interpolation ──────────────────────────────────

    private void runIDWInterpolation() {
        double qE, qN, power;
        try { qE    = Double.parseDouble(param1Field.getText().trim()); } catch(Exception e) { status("Enter Query E (Easting)."); return; }
        try { qN    = Double.parseDouble(param2Field.getText().trim()); } catch(Exception e) { status("Enter Query N (Northing)."); return; }
        try { power = Double.parseDouble(param3Field.getText().trim()); } catch(Exception e) { power = 2; }
        if (power <= 0) power = 2;

        double estimatedZ = SpatialAlgorithms3D.idwInterpolation(points, qE, qN, power);

        StringBuilder sb = new StringBuilder("=== Feature 19: IDW Elevation Interpolation ===\n");
        sb.append(String.format("Query location : E=%.3f, N=%.3f%n", qE, qN));
        sb.append(String.format("IDW power p    : %.1f%n", power));
        sb.append(String.format("Data points    : %d%n%n", points.size()));
        if (Double.isNaN(estimatedZ)) {
            sb.append("Could not interpolate (no valid data points with elevation).\n");
            sb.append("Hint: Load a 3D CSV with ELEVATION column, or manually set\n");
            sb.append("      point elevations before running IDW.\n");
        } else {
            sb.append(String.format("Estimated Elevation: %.3f m%n%n", estimatedZ));
            sb.append("Top-5 contributing points (by weight):\n");
            // Show the 5 nearest points with their weights
            final double p = power;
            List<Integer> nearest = SpatialAlgorithms3D.kNearestNeighbours3D(
                    points, qE, qN, estimatedZ, Math.min(5, points.size()), 0);
            for (int idx : nearest) {
                SpatialPoint pt = points.get(idx);
                double d = SpatialAlgorithms3D.distanceHorizontal(qE, qN, pt.getEasting(), pt.getNorthing());
                double w = 1.0 / Math.pow(Math.max(d, 1e-3), p);
                sb.append(String.format("  %-28s  Z=%.1f m  d=%.1f m%n",
                        pt.getName(), pt.getElevation(), d));
            }
        }
        resultArea.setText(sb.toString());
        mapPanel.setQueryPoint(qE, qN, 0);
        status(Double.isNaN(estimatedZ) ? "IDW: no elevation data available."
                : String.format("IDW interpolated elevation = %.2f m", estimatedZ));
    }

    // ------ Shapefile open ------------------------------------------------------

    private void openShapefile() {
        try {
            status("Loading shapefile via GeoTools…");
            List<SpatialPoint> loaded = ShapefileHandler.loadShapefileWithDialog();
            if (loaded == null) { status("Shapefile load cancelled."); return; }
            if (loaded.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "No point features found in shapefile.\n" +
                    "Only Point/MultiPoint geometry is imported.\n" +
                    "Line and polygon features are skipped.",
                    "No Points", JOptionPane.WARNING_MESSAGE);
                return;
            }
            points = loaded;
            mapPanel.setPoints(points);
            resultArea.setText("Loaded " + points.size() + " features from shapefile (GeoTools).\n\n");
            for (SpatialPoint p : points) resultArea.append("  " + p + "\n");
            status("GeoTools: loaded " + points.size() + " shapefile features.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "GeoTools error reading shapefile:\n" + ex.getMessage(),
                "Shapefile Error", JOptionPane.ERROR_MESSAGE);
        }
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

    // ── Bundled school data auto-loader ──────────────────────────────────────

    private void loadBundledSchoolData() {
        try (java.io.InputStream is =
                MainFrame.class.getResourceAsStream("/data/hk_school.csv")) {
            if (is == null) return;
            List<SpatialPoint> loaded = FileHandler.readCSV(is);
            if (loaded.isEmpty()) return;
            points = loaded;
            mapPanel.setPoints(points);
            resultArea.setText("Auto-loaded " + points.size()
                    + " HK secondary schools.\n"
                    + "Category: STUDENTS GENDER (CO-ED / BOYS / GIRLS)\n"
                    + "Description: DISTRICT\n");
            status("Loaded " + points.size() + " HK secondary schools.");
        } catch (Exception ex) {
            // silent – user can still load manually
        }
    }

    // ── 3D CesiumJS Viewer ────────────────────────────────────────────────────

    private void open3DViewer() {
        status("Opening 3D viewer in browser…");
        new Thread(() -> {
            try {
                ThreeDViewer.launch(points);
                SwingUtilities.invokeLater(() -> status("3D viewer launched in browser."));
            } catch (UnsupportedOperationException ex) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this,
                        ex.getMessage(), "3D Viewer", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this,
                        "Could not open 3D viewer:\n" + ex.getMessage(),
                        "3D Viewer Error", JOptionPane.ERROR_MESSAGE));
            }
        }, "3DViewer-Launch").start();
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

    /** Truncate a string to maxLen characters for table formatting. */
    private static String shorten(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "\u2026";
    }

    /** Returns the GeoTools version string at runtime from the manifest. */
    private static String getGeoToolsVersion() {
        try {
            String v = org.geotools.util.factory.GeoTools.getVersion().toString();
            return v.isEmpty() ? "32.0" : v;
        } catch (Throwable t) {
            return "32.0";
        }
    }
}

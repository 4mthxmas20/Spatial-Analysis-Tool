package hkspatialanalysis;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Custom Swing panel that renders spatial points and algorithm results
 * on an interactive 2-D map canvas supporting pan and zoom.
 *
 * Interaction modes
 *   MODE_SELECT  – click to select a point, drag to pan
 *   MODE_POLYGON – click to add polygon vertices; double-click to close
 *   MODE_QUERY   – click to set query location (used by KNN, Buffer, Polar)
 *   MODE_LINE    – click two pairs of points to define two segments (for intersection)
 */
public class MapPanel extends JPanel
        implements MouseListener, MouseMotionListener, MouseWheelListener {

    public static final int MODE_SELECT  = 0;
    public static final int MODE_POLYGON = 1;
    public static final int MODE_QUERY   = 2;
    public static final int MODE_LINE    = 3;

    // -- data ------------------------------------------------------------------
    private List<SpatialPoint> points     = new ArrayList<>();
    private Set<Integer>       highlighted = new HashSet<>();   // generic highlight
    private List<Integer>      hullIndices = new ArrayList<>(); // convex hull
    private List<int[]>        mstEdges    = new ArrayList<>(); // MST edges
    private double[]           mbr         = null;              // [minE,minN,maxE,maxN]
    private double[]           centroid    = null;              // [E,N]
    private double[]           queryPoint  = null;              // click location
    private double             bufferRadius= 0;
    private double[]           polarResult = null;              // [E,N]
    private double[]           intersectPt = null;              // [E,N]

    // polygon drawn by user
    private List<double[]>     userPolygon = new ArrayList<>();
    private boolean            polygonClosed = false;

    // two-segment definition (line intersection mode)
    // linePoints: 0=A, 1=B, 2=C, 3=D
    private List<double[]>     linePoints  = new ArrayList<>();

    private int mode = MODE_SELECT;
    private int selectedIndex = -1;

    // -- view ------------------------------------------------------------------
    private double offsetX = 0, offsetY = 0; // pan in pixels
    private double scale   = 0.05;           // pixels per metre
    private int    dragStartX, dragStartY;
    private boolean dragging = false;

    // -- colours per category --------------------------------------------------
    private static final String[] CATEGORY_NAMES = {
        "Sports", "Park", "Transport", "Education", "Heritage",
        "Hospital", "Shopping", "Government", "Recreation", "Other"
    };
    private static final Color[] CATEGORY_COLORS = {
        new Color(220,  50,  50),  // Sports      - red
        new Color( 50, 180,  50),  // Park        - green
        new Color( 50, 100, 220),  // Transport   - blue
        new Color(255, 165,   0),  // Education   - orange
        new Color(148,   0, 211),  // Heritage    - purple
        new Color(  0, 200, 200),  // Hospital    - cyan
        new Color(255, 105, 180),  // Shopping    - pink
        new Color(139,  69,  19),  // Government  - brown
        new Color(255, 215,   0),  // Recreation  - gold
        new Color(100, 100, 100)   // Other       - grey
    };

    public MapPanel() {
        setBackground(new Color(240, 245, 250));
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
    }

    // ---- public API ---------------------------------------------------------

    public void setPoints(List<SpatialPoint> pts) {
        this.points = pts;
        highlighted.clear();
        hullIndices.clear();
        mstEdges.clear();
        mbr = null; centroid = null; queryPoint = null;
        bufferRadius = 0; polarResult = null; intersectPt = null;
        userPolygon.clear(); polygonClosed = false;
        linePoints.clear();
        fitToData();
        repaint();
    }

    public void setMode(int m) {
        this.mode = m;
        if (m == MODE_POLYGON) { userPolygon.clear(); polygonClosed = false; }
        if (m == MODE_LINE)    { linePoints.clear(); }
        repaint();
    }

    public int getMode() { return mode; }

    public void setHighlighted(Collection<Integer> idx) {
        highlighted.clear();
        highlighted.addAll(idx);
        repaint();
    }

    public void setHullIndices(List<Integer> hull) {
        this.hullIndices = hull;
        repaint();
    }

    public void setMSTEdges(List<int[]> edges) {
        this.mstEdges = edges;
        repaint();
    }

    public void setMBR(double[] mbr) {
        this.mbr = mbr;
        repaint();
    }

    public void setCentroid(double[] c) {
        this.centroid = c;
        repaint();
    }

    public void setQueryPoint(double e, double n, double radius) {
        this.queryPoint   = new double[]{e, n};
        this.bufferRadius = radius;
        repaint();
    }

    public void setPolarResult(double[] pt) {
        this.polarResult = pt;
        repaint();
    }

    public void setIntersectPoint(double[] pt) {
        this.intersectPt = pt;
        repaint();
    }

    public List<double[]> getUserPolygon() { return userPolygon; }
    public boolean isPolygonClosed()       { return polygonClosed; }
    public List<double[]> getLinePoints()  { return linePoints; }
    public int getSelectedIndex()          { return selectedIndex; }

    public void clearOverlays() {
        highlighted.clear();
        hullIndices.clear();
        mstEdges.clear();
        mbr = null; centroid = null; queryPoint = null;
        bufferRadius = 0; polarResult = null; intersectPt = null;
        userPolygon.clear(); polygonClosed = false;
        linePoints.clear();
        selectedIndex = -1;
        repaint();
    }

    // ---- coordinate conversion ----------------------------------------------

    /** Data (E,N) -> screen (x,y). Y axis is flipped (N increases upward). */
    private int toScreenX(double e) {
        return (int) ((e - originE()) * scale + offsetX);
    }
    private int toScreenY(double n) {
        int h = getHeight() == 0 ? 600 : getHeight();
        return (int) (h - ((n - originN()) * scale + offsetY));
    }

    /** Screen (x,y) -> data (E,N). */
    private double toDataE(int x) {
        return (x - offsetX) / scale + originE();
    }
    private double toDataN(int y) {
        int h = getHeight() == 0 ? 600 : getHeight();
        return (h - y - offsetY) / scale + originN();
    }

    /** Reference origin in data coordinates (bottom-left of initial view). */
    private double dataMinE = 830000, dataMinN = 813000;
    private double originE() { return dataMinE; }
    private double originN() { return dataMinN; }

    private void fitToData() {
        if (points.isEmpty()) return;
        double minE = Double.MAX_VALUE, minN = Double.MAX_VALUE;
        double maxE = -Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
        for (SpatialPoint p : points) {
            minE = Math.min(minE, p.getEasting());
            minN = Math.min(minN, p.getNorthing());
            maxE = Math.max(maxE, p.getEasting());
            maxN = Math.max(maxN, p.getNorthing());
        }
        dataMinE = minE - 500;
        dataMinN = minN - 500;
        double rangeE = maxE - minE + 1000;
        double rangeN = maxN - minN + 1000;
        int w = getWidth()  == 0 ? 800 : getWidth();
        int h = getHeight() == 0 ? 600 : getHeight();
        scale   = Math.min(w / rangeE, h / rangeN) * 0.85;
        offsetX = (w  - rangeE * scale) / 2.0;
        offsetY = (h  - rangeN * scale) / 2.0;
    }

    // ---- painting -----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g2);
        drawMBR(g2);
        drawMST(g2);
        drawConvexHull(g2);
        drawUserPolygon(g2);
        drawLineSegments(g2);
        drawBufferCircle(g2);
        drawPoints(g2);
        drawQueryPoint(g2);
        drawPolarResult(g2);
        drawIntersectPoint(g2);
        drawCentroid(g2);
        drawLegend(g2);
        drawModeHint(g2);
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(new Color(200, 210, 220));
        g2.setStroke(new BasicStroke(0.5f));
        // vertical grid lines every 1000 m
        for (double e = Math.floor(dataMinE / 1000) * 1000; ; e += 1000) {
            int x = toScreenX(e);
            if (x > getWidth() + 50) break;
            g2.drawLine(x, 0, x, getHeight());
            g2.setColor(new Color(150, 160, 170));
            g2.drawString(String.format("%.0f", e), x + 2, getHeight() - 2);
            g2.setColor(new Color(200, 210, 220));
        }
        for (double n = Math.floor(dataMinN / 1000) * 1000; ; n += 1000) {
            int y = toScreenY(n);
            if (y < -50) break;
            g2.drawLine(0, y, getWidth(), y);
            g2.setColor(new Color(150, 160, 170));
            g2.drawString(String.format("%.0f", n), 2, y - 2);
            g2.setColor(new Color(200, 210, 220));
        }
    }

    private void drawPoints(Graphics2D g2) {
        for (int i = 0; i < points.size(); i++) {
            SpatialPoint p = points.get(i);
            int sx = toScreenX(p.getEasting());
            int sy = toScreenY(p.getNorthing());
            Color c = getCategoryColor(p.getCategory());

            boolean hl = highlighted.contains(i);
            boolean sel = (i == selectedIndex);

            if (hl) {
                g2.setColor(Color.YELLOW);
                g2.fillOval(sx - 9, sy - 9, 18, 18);
            }
            if (sel) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(sx - 7, sy - 7, 14, 14);
            }
            g2.setColor(c);
            g2.fillOval(sx - 5, sy - 5, 10, 10);
            g2.setColor(c.darker());
            g2.setStroke(new BasicStroke(1f));
            g2.drawOval(sx - 5, sy - 5, 10, 10);

            // label
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font("Arial", Font.PLAIN, 10));
            g2.drawString(p.getName(), sx + 7, sy + 4);
        }
    }

    private void drawConvexHull(Graphics2D g2) {
        if (hullIndices.size() < 2) return;
        g2.setColor(new Color(0, 120, 255, 180));
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int[] xs = new int[hullIndices.size()];
        int[] ys = new int[hullIndices.size()];
        for (int k = 0; k < hullIndices.size(); k++) {
            int idx = hullIndices.get(k);
            xs[k] = toScreenX(points.get(idx).getEasting());
            ys[k] = toScreenY(points.get(idx).getNorthing());
        }
        g2.drawPolygon(xs, ys, xs.length);
        // fill with transparent blue
        g2.setColor(new Color(100, 180, 255, 40));
        g2.fillPolygon(xs, ys, xs.length);
    }

    private void drawMST(Graphics2D g2) {
        if (mstEdges.isEmpty()) return;
        g2.setColor(new Color(200, 100, 0, 200));
        g2.setStroke(new BasicStroke(1.5f));
        for (int[] edge : mstEdges) {
            SpatialPoint a = points.get(edge[0]);
            SpatialPoint b = points.get(edge[1]);
            g2.drawLine(toScreenX(a.getEasting()), toScreenY(a.getNorthing()),
                        toScreenX(b.getEasting()), toScreenY(b.getNorthing()));
        }
    }

    private void drawMBR(Graphics2D g2) {
        if (mbr == null) return;
        int x1 = toScreenX(mbr[0]), y1 = toScreenY(mbr[3]); // top-left on screen
        int x2 = toScreenX(mbr[2]), y2 = toScreenY(mbr[1]); // bottom-right on screen
        g2.setColor(new Color(180, 0, 180, 60));
        g2.fillRect(Math.min(x1,x2), Math.min(y1,y2),
                    Math.abs(x2-x1), Math.abs(y2-y1));
        g2.setColor(new Color(180, 0, 180));
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                     1f, new float[]{6,4}, 0));
        g2.drawRect(Math.min(x1,x2), Math.min(y1,y2),
                    Math.abs(x2-x1), Math.abs(y2-y1));
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(180, 0, 180));
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        g2.drawString("MBR", Math.min(x1,x2) + 3, Math.min(y1,y2) - 3);
    }

    private void drawUserPolygon(Graphics2D g2) {
        if (userPolygon.isEmpty()) return;
        int n = userPolygon.size();
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = toScreenX(userPolygon.get(i)[0]);
            ys[i] = toScreenY(userPolygon.get(i)[1]);
        }
        g2.setColor(new Color(255, 140, 0, 50));
        if (polygonClosed) g2.fillPolygon(xs, ys, n);
        g2.setColor(new Color(255, 100, 0));
        g2.setStroke(new BasicStroke(2f));
        if (polygonClosed) g2.drawPolygon(xs, ys, n);
        else               g2.drawPolyline(xs, ys, n);

        // vertex markers
        g2.setColor(Color.RED);
        for (int i = 0; i < n; i++) {
            g2.fillOval(xs[i]-4, ys[i]-4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 9));
            g2.drawString(String.valueOf(i+1), xs[i]+5, ys[i]+4);
            g2.setColor(Color.RED);
        }
    }

    private void drawBufferCircle(Graphics2D g2) {
        if (queryPoint == null || bufferRadius <= 0) return;
        int cx = toScreenX(queryPoint[0]);
        int cy = toScreenY(queryPoint[1]);
        int r  = (int) (bufferRadius * scale);
        g2.setColor(new Color(0, 200, 100, 40));
        g2.fillOval(cx - r, cy - r, 2*r, 2*r);
        g2.setColor(new Color(0, 160, 60));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawOval(cx - r, cy - r, 2*r, 2*r);
    }

    private void drawQueryPoint(Graphics2D g2) {
        if (queryPoint == null) return;
        int cx = toScreenX(queryPoint[0]);
        int cy = toScreenY(queryPoint[1]);
        g2.setColor(Color.MAGENTA);
        int sz = 10;
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(cx - sz, cy, cx + sz, cy);
        g2.drawLine(cx, cy - sz, cx, cy + sz);
        g2.fillOval(cx - 4, cy - 4, 8, 8);
    }

    private void drawPolarResult(Graphics2D g2) {
        if (polarResult == null || selectedIndex < 0) return;
        SpatialPoint origin = points.get(selectedIndex);
        int ox = toScreenX(origin.getEasting()),  oy = toScreenY(origin.getNorthing());
        int px = toScreenX(polarResult[0]),        py = toScreenY(polarResult[1]);
        g2.setColor(new Color(150, 0, 200));
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(ox, oy, px, py);
        // arrowhead
        double angle = Math.atan2(py - oy, px - ox);
        int a1x = px - (int)(12 * Math.cos(angle - 0.4));
        int a1y = py - (int)(12 * Math.sin(angle - 0.4));
        int a2x = px - (int)(12 * Math.cos(angle + 0.4));
        int a2y = py - (int)(12 * Math.sin(angle + 0.4));
        g2.drawLine(px, py, a1x, a1y);
        g2.drawLine(px, py, a2x, a2y);
        g2.setColor(Color.MAGENTA);
        g2.fillOval(px - 5, py - 5, 10, 10);
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        g2.drawString("Polar Result", px + 7, py + 4);
    }

    private void drawIntersectPoint(Graphics2D g2) {
        if (intersectPt == null) return;
        int px = toScreenX(intersectPt[0]);
        int py = toScreenY(intersectPt[1]);
        g2.setColor(Color.RED);
        g2.setStroke(new BasicStroke(2.5f));
        int s = 8;
        g2.drawLine(px-s, py-s, px+s, py+s);
        g2.drawLine(px-s, py+s, px+s, py-s);
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        g2.drawString("Intersection", px + 10, py - 5);
    }

    private void drawCentroid(Graphics2D g2) {
        if (centroid == null) return;
        int cx = toScreenX(centroid[0]);
        int cy = toScreenY(centroid[1]);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2f));
        int s = 8;
        g2.drawLine(cx-s, cy, cx+s, cy);
        g2.drawLine(cx, cy-s, cx, cy+s);
        g2.setColor(new Color(50, 50, 50));
        g2.fillOval(cx-4, cy-4, 8, 8);
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        g2.drawString("Centroid", cx+7, cy-4);
    }

    private void drawLineSegments(Graphics2D g2) {
        if (linePoints.isEmpty()) return;
        Color[] lc = {new Color(0,0,200), new Color(200,0,0)};
        for (int seg = 0; seg < 2; seg++) {
            int start = seg * 2;
            if (linePoints.size() > start) {
                int sx = toScreenX(linePoints.get(start)[0]);
                int sy = toScreenY(linePoints.get(start)[1]);
                g2.setColor(lc[seg]);
                g2.fillOval(sx-4, sy-4, 8, 8);
                if (linePoints.size() > start + 1) {
                    int ex = toScreenX(linePoints.get(start+1)[0]);
                    int ey = toScreenY(linePoints.get(start+1)[1]);
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawLine(sx, sy, ex, ey);
                    g2.fillOval(ex-4, ey-4, 8, 8);
                }
            }
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawLegend(Graphics2D g2) {
        // Collect categories actually present
        Set<String> cats = new LinkedHashSet<>();
        for (SpatialPoint p : points) cats.add(p.getCategory());
        if (cats.isEmpty()) return;
        int x = 10, y = 15;
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.setColor(new Color(50,50,50,200));
        g2.fillRoundRect(x-4, y-13, 130, cats.size()*16+8, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString("Categories", x+2, y);
        y += 3;
        g2.setFont(new Font("Arial", Font.PLAIN, 10));
        for (String cat : cats) {
            y += 15;
            g2.setColor(getCategoryColor(cat));
            g2.fillOval(x, y-8, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(cat, x+14, y);
        }
    }

    private void drawModeHint(Graphics2D g2) {
        String hint = "";
        switch (mode) {
            case MODE_SELECT:  hint = "Click point to select. Drag to pan."; break;
            case MODE_POLYGON: hint = "Click to add vertices. Double-click to close polygon."; break;
            case MODE_QUERY:   hint = "Click to set query location."; break;
            case MODE_LINE:    hint = "Click to define line A (2 pts) then line B (2 pts)."; break;
        }
        g2.setFont(new Font("Arial", Font.ITALIC, 11));
        g2.setColor(new Color(0, 0, 0, 120));
        g2.drawString(hint, 10, getHeight() - 8);
    }

    // ---- mouse events -------------------------------------------------------

    @Override public void mousePressed(MouseEvent e) {
        dragStartX = e.getX(); dragStartY = e.getY();
        dragging = false;
    }

    @Override public void mouseReleased(MouseEvent e) {
        if (!dragging) handleClick(e);
    }

    @Override public void mouseDragged(MouseEvent e) {
        dragging = true;
        if (mode == MODE_SELECT) {
            offsetX += e.getX() - dragStartX;
            offsetY += e.getY() - dragStartY;  // note: y inversion handled in toScreenY
            // Actually we need to adjust offsetY in data space direction
            // Re-do: offsetY is added in toScreenY as: h - ((n-originN)*scale + offsetY)
            // dragging down means offsetY decreases
            offsetY -= (e.getY() - dragStartY);
            offsetY += (e.getY() - dragStartY); // cancel above; simpler below
        }
        // Simple pan regardless of mode when dragging
        offsetX += e.getX() - dragStartX;
        offsetY -= e.getY() - dragStartY; // north is up → invert y
        dragStartX = e.getX(); dragStartY = e.getY();
        dragging = true;
        repaint();
    }

    private void handleClick(MouseEvent e) {
        double clickE = toDataE(e.getX());
        double clickN = toDataN(e.getY());

        if (mode == MODE_SELECT) {
            // Find closest point within 15 px
            double threshold = 15.0 / scale;
            selectedIndex = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double d = SpatialAlgorithms.distance(clickE, clickN,
                        points.get(i).getEasting(), points.get(i).getNorthing());
                if (d < best && d < threshold) { best = d; selectedIndex = i; }
            }
            repaint();
            // Notify parent
            firePropertyChange("selectedPoint", -2, selectedIndex);

        } else if (mode == MODE_POLYGON) {
            if (e.getClickCount() == 2 && userPolygon.size() >= 3) {
                polygonClosed = true;
                firePropertyChange("polygonClosed", false, true);
            } else {
                userPolygon.add(new double[]{clickE, clickN});
            }
            repaint();

        } else if (mode == MODE_QUERY) {
            queryPoint = new double[]{clickE, clickN};
            repaint();
            firePropertyChange("queryPoint", null, queryPoint);

        } else if (mode == MODE_LINE) {
            if (linePoints.size() < 4) {
                linePoints.add(new double[]{clickE, clickN});
            } else {
                linePoints.clear();
                linePoints.add(new double[]{clickE, clickN});
            }
            repaint();
            if (linePoints.size() == 4) {
                firePropertyChange("lineDefined", false, true);
            }
        }
    }

    @Override public void mouseWheelMoved(MouseWheelEvent e) {
        double factor = (e.getWheelRotation() < 0) ? 1.15 : 0.87;
        // zoom around mouse pointer
        double mouseE = toDataE(e.getX());
        double mouseN = toDataN(e.getY());
        scale *= factor;
        // adjust offsets so the point under cursor stays fixed
        offsetX = e.getX() - (mouseE - originE()) * scale;
        offsetY = (getHeight() - e.getY()) - (mouseN - originN()) * scale;
        repaint();
    }

    @Override public void mouseMoved(MouseEvent e)  {}
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e)  {}

    // ---- helpers ------------------------------------------------------------

    private Color getCategoryColor(String cat) {
        for (int i = 0; i < CATEGORY_NAMES.length; i++) {
            if (CATEGORY_NAMES[i].equalsIgnoreCase(cat)) return CATEGORY_COLORS[i];
        }
        return CATEGORY_COLORS[CATEGORY_COLORS.length - 1];
    }
}

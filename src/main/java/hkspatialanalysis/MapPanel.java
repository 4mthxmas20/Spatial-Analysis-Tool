package hkspatialanalysis;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Interactive 2-D map canvas with:
 *   - Slippy-map tile basemap (CartoDB / OSM, fetched asynchronously)
 *   - HK1980 Grid point rendering with per-category colours
 *   - Zoom (mouse wheel) and pan (drag)
 *   - Overlay layers: convex hull, MST, MBR, user polygon, buffer circle,
 *     polar vector, line segments, intersection point, centroid
 *
 * Coordinate systems
 *   Data space  : HK1980 Grid (EPSG:2326) in metres (easting E, northing N)
 *   Screen space: pixels, origin top-left, Y increases downward
 *   Geographic  : WGS84 lat/lon (EPSG:4326), used for tile look-up only
 *
 * CRS conversion pipeline (runs once in a daemon thread on construction):
 *   HK1980 (E,N) → GeoTools CRSTransformer → WGS84 (lat, lon)
 *                → TileMapProvider math → tile (x, y, zoom)
 */
public class MapPanel extends JPanel
        implements MouseListener, MouseMotionListener, MouseWheelListener {

    // ── interaction modes ────────────────────────────────────────────────────
    public static final int MODE_SELECT  = 0;
    public static final int MODE_POLYGON = 1;
    public static final int MODE_QUERY   = 2;
    public static final int MODE_LINE    = 3;

    // ── data layers ──────────────────────────────────────────────────────────
    private List<SpatialPoint> points      = new ArrayList<>();
    private Set<Integer>       highlighted = new HashSet<>();
    private List<Integer>      hullIndices = new ArrayList<>();
    private List<int[]>        mstEdges    = new ArrayList<>();
    private double[]           mbr         = null;
    private double[]           centroid    = null;
    private double[]           queryPoint  = null;
    private double             bufferRadius= 0;
    private double[]           polarResult = null;
    private double[]           intersectPt = null;
    private List<double[]>     userPolygon = new ArrayList<>();
    private boolean            polygonClosed = false;
    private List<double[]>     linePoints  = new ArrayList<>();

    // ── interaction ──────────────────────────────────────────────────────────
    private int  mode          = MODE_SELECT;
    private int  selectedIndex = -1;
    private int  dragStartX, dragStartY;
    private boolean dragging   = false;

    // ── view transform ───────────────────────────────────────────────────────
    /** Pixel offset of data-space origin from panel top-left. */
    private double offsetX = 0, offsetY = 0;
    /** Pixels per HK1980 metre. */
    private double scale   = 0.05;
    /** Data-space origin (bottom-left of initial view, in HK1980 metres). */
    private double originE = 830000, originN = 813000;

    // ── basemap ──────────────────────────────────────────────────────────────
    private final TileMapProvider tileProvider = new TileMapProvider();
    private boolean showBasemap   = true;
    /** When true, points are coloured by description (e.g. District) instead of category. */
    private boolean colorByDesc   = false;
    /** Set to true once the GeoTools CRS transform has been warmed up. */
    private volatile boolean crsReady = false;

    // ── category colours ─────────────────────────────────────────────────────
    // School gender categories (primary)
    private static final String[] CAT_NAMES = {
        // ── School: gender ────────────────────────────────────────────────
        "CO-ED", "BOYS", "GIRLS",
        // ── School: districts ─────────────────────────────────────────────
        "CENTRAL AND WESTERN","WAN CHAI","EASTERN","SOUTHERN",
        "YAU TSIM MONG","SHAM SHUI PO","KOWLOON CITY","WONG TAI SIN","KWUN TONG",
        "TSUEN WAN","TUEN MUN","YUEN LONG","NORTH","TAI PO","SHA TIN","SAI KUNG","ISLANDS",
        "KWAI TSING",
        // ── General POI ───────────────────────────────────────────────────
        "Sports","Park","Transport","Education","Heritage",
        "Hospital","Shopping","Government","Recreation","Other"
    };
    private static final Color[] CAT_COLORS = {
        // Gender
        new Color( 98, 72,224),   // CO-ED  – purple
        new Color( 30,120,220),   // BOYS   – blue
        new Color(220, 45,120),   // GIRLS  – pink
        // Districts (18 HK districts – distinct hues around the colour wheel)
        new Color(220, 60, 60),   // CENTRAL AND WESTERN
        new Color(240,120, 20),   // WAN CHAI
        new Color(200,180,  0),   // EASTERN
        new Color( 80,180, 40),   // SOUTHERN
        new Color( 30,160,100),   // YAU TSIM MONG
        new Color(  0,185,175),   // SHAM SHUI PO
        new Color( 20,140,220),   // KOWLOON CITY
        new Color( 90, 90,220),   // WONG TAI SIN
        new Color(160, 40,200),   // KWUN TONG
        new Color(200, 50,160),   // TSUEN WAN
        new Color(220, 80, 80),   // TUEN MUN
        new Color(240,150,  0),   // YUEN LONG
        new Color(160,200, 20),   // NORTH
        new Color( 40,190, 80),   // TAI PO
        new Color(  0,180,160),   // SHA TIN
        new Color( 30,130,210),   // SAI KUNG
        new Color(110, 70,200),   // ISLANDS
        new Color(190, 40,190),   // KWAI TSING
        // General POI (legacy)
        new Color(220, 50, 50), new Color(50,180,50),  new Color(50,100,220),
        new Color(255,165,  0), new Color(148,  0,211), new Color(  0,200,200),
        new Color(255,105,180), new Color(139, 69, 19), new Color(255,215,  0),
        new Color(100,100,100)
    };

    // ── constructor ──────────────────────────────────────────────────────────
    public MapPanel() {
        setBackground(new Color(240, 245, 250));
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);

        // Warm up GeoTools CRS factory in a daemon thread so the first
        // basemap draw does not block the Event Dispatch Thread.
        Thread warmup = new Thread(() -> {
            try {
                CRSTransformer.hk1980ToWGS84(836000, 819000);
                CRSTransformer.wgs84ToHK1980(22.31, 114.18);
                crsReady = true;
                SwingUtilities.invokeLater(this::repaint);
            } catch (Exception ignored) {
                // GeoTools unavailable – basemap won't show
            }
        }, "CRS-Warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    // ── public setters ───────────────────────────────────────────────────────

    public void setPoints(List<SpatialPoint> pts) {
        this.points = pts;
        clearAllOverlays();
        fitToData();
        repaint();
    }

    public void setMode(int m) {
        mode = m;
        if (m == MODE_POLYGON) { userPolygon.clear(); polygonClosed = false; }
        if (m == MODE_LINE)    linePoints.clear();
        repaint();
    }

    public void setTileServer(TileMapProvider.TileServer s) {
        tileProvider.setServer(s);
        repaint();
    }

    public void setShowBasemap(boolean show)  { showBasemap = show;   repaint(); }
    public boolean isShowBasemap()            { return showBasemap; }
    public void setColorByDesc(boolean byDesc){ colorByDesc = byDesc; repaint(); }
    public boolean isColorByDesc()            { return colorByDesc; }

    public void setHighlighted(Collection<Integer> idx) {
        highlighted.clear(); highlighted.addAll(idx); repaint();
    }
    public void setHullIndices(List<Integer> hull) { hullIndices = hull; repaint(); }
    public void setMSTEdges(List<int[]> edges)     { mstEdges = edges;   repaint(); }
    public void setMBR(double[] m)                 { mbr = m;            repaint(); }
    public void setCentroid(double[] c)            { centroid = c;        repaint(); }
    public void setQueryPoint(double e, double n, double r) {
        queryPoint = new double[]{e, n}; bufferRadius = r; repaint();
    }
    public void setPolarResult(double[] pt)   { polarResult = pt;  repaint(); }
    public void setIntersectPoint(double[] pt){ intersectPt = pt;  repaint(); }

    public List<double[]> getUserPolygon()  { return userPolygon; }
    public boolean isPolygonClosed()        { return polygonClosed; }
    public List<double[]> getLinePoints()   { return linePoints; }
    public int getSelectedIndex()           { return selectedIndex; }
    public TileMapProvider getTileProvider(){ return tileProvider; }

    public void clearOverlays() { clearAllOverlays(); repaint(); }

    private void clearAllOverlays() {
        highlighted.clear(); hullIndices.clear(); mstEdges.clear();
        mbr = null; centroid = null; queryPoint = null;
        bufferRadius = 0; polarResult = null; intersectPt = null;
        userPolygon.clear(); polygonClosed = false; linePoints.clear();
        selectedIndex = -1;
    }

    // ── coordinate conversion ─────────────────────────────────────────────────

    private int toScreenX(double e) {
        return (int) ((e - originE) * scale + offsetX);
    }
    private int toScreenY(double n) {
        return (int) (getHeight() - ((n - originN) * scale + offsetY));
    }
    private double toDataE(int x) { return (x - offsetX) / scale + originE; }
    private double toDataN(int y) { return (getHeight() - y - offsetY) / scale + originN; }

    private void fitToData() {
        if (points.isEmpty()) return;
        double minE = Double.MAX_VALUE, minN = Double.MAX_VALUE;
        double maxE = -Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
        for (SpatialPoint p : points) {
            minE = Math.min(minE, p.getEasting());  minN = Math.min(minN, p.getNorthing());
            maxE = Math.max(maxE, p.getEasting());  maxN = Math.max(maxN, p.getNorthing());
        }
        originE = minE - 500;  originN = minN - 500;
        double rangeE = maxE - minE + 1000, rangeN = maxN - minN + 1000;
        int w = getWidth() == 0 ? 900 : getWidth();
        int h = getHeight() == 0 ? 600 : getHeight();
        scale   = Math.min(w / rangeE, h / rangeN) * 0.85;
        offsetX = (w - rangeE * scale) / 2.0;
        offsetY = (h - rangeN * scale) / 2.0;
    }

    // ── painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        drawBasemap(g2);              // ← tile background (GeoTools + TileMapProvider)
        if (!showBasemap) drawGrid(g2);
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
        drawAttribution(g2);          // required by CartoDB / OSM tile ToS
    }

    // ── basemap tile layer ────────────────────────────────────────────────────

    private void drawBasemap(Graphics2D g2) {
        if (!showBasemap) return;

        if (!crsReady) {
            g2.setColor(new Color(220, 228, 236));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new Color(130, 150, 170));
            g2.setFont(new Font("Arial", Font.ITALIC, 13));
            String msg = "Loading basemap CRS…";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
            return;
        }

        try {
            // 1. Screen-corner data coordinates
            double minE = toDataE(0),          maxE = toDataE(getWidth());
            double minN = toDataN(getHeight()), maxN = toDataN(0);

            // 2. Convert to WGS84 (lat, lon) using GeoTools
            double[] sw = CRSTransformer.hk1980ToWGS84(minE, minN);
            double[] ne = CRSTransformer.hk1980ToWGS84(maxE, maxN);
            double minLat = Math.min(sw[0], ne[0]), maxLat = Math.max(sw[0], ne[0]);
            double minLon = Math.min(sw[1], ne[1]), maxLon = Math.max(sw[1], ne[1]);

            // 3. Choose zoom level to match current scale
            int zoom = TileMapProvider.scaleToZoom(scale, (minLat + maxLat) / 2.0);

            // 4. Tile range covering this view
            int tx0 = TileMapProvider.lonToTileX(minLon, zoom);
            int tx1 = TileMapProvider.lonToTileX(maxLon, zoom);
            int ty0 = TileMapProvider.latToTileY(maxLat, zoom); // high lat → low row
            int ty1 = TileMapProvider.latToTileY(minLat, zoom);
            if ((long)(tx1-tx0+1) * (ty1-ty0+1) > 200) return; // safety guard

            // 5. Draw each tile
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    BufferedImage tile = tileProvider.getTileAsync(tx, ty, zoom, this::repaint);

                    double lon0 = TileMapProvider.tileXToLon(tx,     zoom);
                    double lat0 = TileMapProvider.tileYToLat(ty,     zoom); // north
                    double lon1 = TileMapProvider.tileXToLon(tx + 1, zoom);
                    double lat1 = TileMapProvider.tileYToLat(ty + 1, zoom); // south
                    int[] r = tileToScreen(lat0, lon0, lat1, lon1);

                    if (tile == null) {
                        // Placeholder while downloading
                        g2.setColor(new Color(210, 218, 226));
                        g2.fillRect(r[0], r[1], r[2]-r[0], r[3]-r[1]);
                        g2.setColor(new Color(180, 192, 204));
                        g2.drawRect(r[0], r[1], r[2]-r[0], r[3]-r[1]);
                    } else {
                        g2.drawImage(tile, r[0], r[1], r[2]-r[0], r[3]-r[1], this);
                    }
                }
            }
            // Pre-fetch adjacent tiles for smoother panning
            tileProvider.prefetch(tx0-1, ty0-1, tx1+1, ty1+1, zoom);

        } catch (Exception e) {
            drawGrid(g2); // fallback if CRS fails
        }
    }

    /** Map a tile's WGS84 bounding box to screen-pixel rect [x0,y0,x1,y1]. */
    private int[] tileToScreen(double lat0, double lon0, double lat1, double lon1)
            throws Exception {
        double[] tl = CRSTransformer.wgs84ToHK1980(lat0, lon0); // [E, N]
        double[] br = CRSTransformer.wgs84ToHK1980(lat1, lon1);
        return new int[]{ toScreenX(tl[0]), toScreenY(tl[1]),
                          toScreenX(br[0]), toScreenY(br[1]) };
    }

    // ── grid (shown when basemap is off) ─────────────────────────────────────

    private void drawGrid(Graphics2D g2) {
        g2.setColor(new Color(200, 210, 220));
        g2.setStroke(new BasicStroke(0.5f));
        g2.setFont(new Font("Arial", Font.PLAIN, 9));
        for (double e = Math.floor(originE/1000)*1000; ; e += 1000) {
            int x = toScreenX(e); if (x > getWidth()+50) break;
            g2.drawLine(x, 0, x, getHeight());
            g2.setColor(new Color(130,148,165));
            g2.drawString(String.format("%.0f", e), x+2, getHeight()-2);
            g2.setColor(new Color(200,210,220));
        }
        for (double n = Math.floor(originN/1000)*1000; ; n += 1000) {
            int y = toScreenY(n); if (y < -50) break;
            g2.drawLine(0, y, getWidth(), y);
            g2.setColor(new Color(130,148,165));
            g2.drawString(String.format("%.0f", n), 2, y-2);
            g2.setColor(new Color(200,210,220));
        }
    }

    // ── point layer ───────────────────────────────────────────────────────────

    private void drawPoints(Graphics2D g2) {
        for (int i = 0; i < points.size(); i++) {
            SpatialPoint p  = points.get(i);
            int sx = toScreenX(p.getEasting()), sy = toScreenY(p.getNorthing());
            // Color either by category (gender) or by description (district)
            Color c = colorByDesc
                    ? categoryColor(p.getDescription().toUpperCase())
                    : categoryColor(p.getCategory().toUpperCase());
            boolean hl  = highlighted.contains(i);
            boolean sel = (i == selectedIndex);

            if (hl)  { g2.setColor(new Color(255,220,0,200)); g2.fillOval(sx-10,sy-10,20,20); }
            if (sel) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawOval(sx-8, sy-8, 16, 16);
                g2.setStroke(new BasicStroke(1f));
            }
            g2.setColor(c);
            g2.fillOval(sx-5, sy-5, 10, 10);
            g2.setColor(c.darker());
            g2.drawOval(sx-5, sy-5, 10, 10);

            // Label with white shadow for legibility on dark/light basemaps
            g2.setFont(new Font("Arial", Font.BOLD, 10));
            g2.setColor(new Color(255,255,255,190));
            g2.drawString(p.getName(), sx+8, sy+5);
            g2.setColor(new Color(20,20,20));
            g2.drawString(p.getName(), sx+7, sy+4);
        }
    }

    // ── overlay layers ────────────────────────────────────────────────────────

    private void drawConvexHull(Graphics2D g2) {
        if (hullIndices.size() < 2) return;
        int n = hullIndices.size();
        int[] xs = new int[n], ys = new int[n];
        for (int k = 0; k < n; k++) {
            xs[k] = toScreenX(points.get(hullIndices.get(k)).getEasting());
            ys[k] = toScreenY(points.get(hullIndices.get(k)).getNorthing());
        }
        g2.setColor(new Color(0,120,255,35));
        g2.fillPolygon(xs, ys, n);
        g2.setColor(new Color(0,80,220,200));
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawPolygon(xs, ys, n);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawMST(Graphics2D g2) {
        if (mstEdges.isEmpty()) return;
        g2.setColor(new Color(200,80,0,200));
        g2.setStroke(new BasicStroke(1.8f));
        for (int[] e : mstEdges) {
            g2.drawLine(toScreenX(points.get(e[0]).getEasting()), toScreenY(points.get(e[0]).getNorthing()),
                        toScreenX(points.get(e[1]).getEasting()), toScreenY(points.get(e[1]).getNorthing()));
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawMBR(Graphics2D g2) {
        if (mbr == null) return;
        int x0=toScreenX(mbr[0]),y0=toScreenY(mbr[3]),x1=toScreenX(mbr[2]),y1=toScreenY(mbr[1]);
        int rx=Math.min(x0,x1), ry=Math.min(y0,y1), rw=Math.abs(x1-x0), rh=Math.abs(y1-y0);
        g2.setColor(new Color(180,0,180,40));   g2.fillRect(rx,ry,rw,rh);
        g2.setColor(new Color(180,0,180));
        g2.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,1f,new float[]{6,4},0));
        g2.drawRect(rx,ry,rw,rh);
        g2.setStroke(new BasicStroke(1f));
        g2.setFont(new Font("Arial",Font.BOLD,10));
        g2.drawString("MBR",rx+3,ry-3);
    }

    private void drawUserPolygon(Graphics2D g2) {
        if (userPolygon.isEmpty()) return;
        int n=userPolygon.size();
        int[] xs=new int[n], ys=new int[n];
        for (int i=0;i<n;i++){xs[i]=toScreenX(userPolygon.get(i)[0]);ys[i]=toScreenY(userPolygon.get(i)[1]);}
        g2.setColor(new Color(255,100,0,45));
        if (polygonClosed) g2.fillPolygon(xs,ys,n);
        g2.setColor(new Color(220,80,0));
        g2.setStroke(new BasicStroke(2f));
        if (polygonClosed) g2.drawPolygon(xs,ys,n); else g2.drawPolyline(xs,ys,n);
        g2.setColor(Color.RED);
        for (int i=0;i<n;i++){
            g2.fillOval(xs[i]-4,ys[i]-4,8,8);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial",Font.PLAIN,9));
            g2.drawString(String.valueOf(i+1),xs[i]+5,ys[i]+4);
            g2.setColor(Color.RED);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawBufferCircle(Graphics2D g2) {
        if (queryPoint==null||bufferRadius<=0) return;
        int cx=toScreenX(queryPoint[0]), cy=toScreenY(queryPoint[1]), r=(int)(bufferRadius*scale);
        g2.setColor(new Color(0,200,100,35));  g2.fillOval(cx-r,cy-r,2*r,2*r);
        g2.setColor(new Color(0,150,60));
        g2.setStroke(new BasicStroke(1.5f));   g2.drawOval(cx-r,cy-r,2*r,2*r);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawQueryPoint(Graphics2D g2) {
        if (queryPoint==null) return;
        int cx=toScreenX(queryPoint[0]), cy=toScreenY(queryPoint[1]);
        g2.setColor(Color.MAGENTA);
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(cx-10,cy,cx+10,cy); g2.drawLine(cx,cy-10,cx,cy+10);
        g2.fillOval(cx-4,cy-4,8,8);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawPolarResult(Graphics2D g2) {
        if (polarResult==null||selectedIndex<0) return;
        SpatialPoint o=points.get(selectedIndex);
        int ox=toScreenX(o.getEasting()), oy=toScreenY(o.getNorthing());
        int px=toScreenX(polarResult[0]),  py=toScreenY(polarResult[1]);
        g2.setColor(new Color(130,0,200));
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(ox,oy,px,py);
        double ang=Math.atan2(py-oy,px-ox);
        g2.drawLine(px,py,px-(int)(12*Math.cos(ang-0.4)),py-(int)(12*Math.sin(ang-0.4)));
        g2.drawLine(px,py,px-(int)(12*Math.cos(ang+0.4)),py-(int)(12*Math.sin(ang+0.4)));
        g2.setColor(Color.MAGENTA);   g2.fillOval(px-5,py-5,10,10);
        g2.setFont(new Font("Arial",Font.BOLD,10));
        g2.drawString("Polar Result",px+7,py+4);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawIntersectPoint(Graphics2D g2) {
        if (intersectPt==null) return;
        int px=toScreenX(intersectPt[0]), py=toScreenY(intersectPt[1]);
        g2.setColor(Color.RED);
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawLine(px-8,py-8,px+8,py+8); g2.drawLine(px-8,py+8,px+8,py-8);
        g2.setFont(new Font("Arial",Font.BOLD,10));
        g2.drawString("Intersection",px+10,py-5);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawCentroid(Graphics2D g2) {
        if (centroid==null) return;
        int cx=toScreenX(centroid[0]), cy=toScreenY(centroid[1]);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(cx-8,cy,cx+8,cy); g2.drawLine(cx,cy-8,cx,cy+8);
        g2.fillOval(cx-4,cy-4,8,8);
        g2.setFont(new Font("Arial",Font.BOLD,10));
        g2.drawString("Centroid",cx+7,cy-4);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawLineSegments(Graphics2D g2) {
        if (linePoints.isEmpty()) return;
        Color[] lc={new Color(0,0,200),new Color(200,0,0)};
        for (int seg=0;seg<2;seg++){
            int start=seg*2;
            if (linePoints.size()>start){
                int sx=toScreenX(linePoints.get(start)[0]),sy=toScreenY(linePoints.get(start)[1]);
                g2.setColor(lc[seg]); g2.fillOval(sx-4,sy-4,8,8);
                if (linePoints.size()>start+1){
                    int ex=toScreenX(linePoints.get(start+1)[0]),ey=toScreenY(linePoints.get(start+1)[1]);
                    g2.setStroke(new BasicStroke(2f)); g2.drawLine(sx,sy,ex,ey);
                    g2.fillOval(ex-4,ey-4,8,8); g2.setStroke(new BasicStroke(1f));
                }
            }
        }
    }

    private void drawLegend(Graphics2D g2) {
        // Collect unique values for whichever colour axis is active
        Set<String> cats = new LinkedHashSet<>();
        for (SpatialPoint p : points)
            cats.add(colorByDesc ? p.getDescription().toUpperCase() : p.getCategory().toUpperCase());
        if (cats.isEmpty()) return;

        String title = colorByDesc ? "District" : "Category";
        int x = 10, y = 16;
        int bh = cats.size() * 15 + 18;
        int maxW = 0;
        g2.setFont(new Font("Arial", Font.PLAIN, 10));
        for (String s : cats) maxW = Math.max(maxW, g2.getFontMetrics().stringWidth(s));
        int boxW = Math.max(maxW + 28, 120);

        g2.setColor(new Color(15, 20, 40, 185));
        g2.fillRoundRect(x-4, y-14, boxW, bh, 8, 8);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.drawString(title, x+2, y);
        g2.setFont(new Font("Arial", Font.PLAIN, 10));
        for (String cat : cats) {
            y += 14;
            Color c = colorByDesc ? categoryColor(cat) : categoryColor(cat);
            g2.setColor(c);
            g2.fillOval(x, y-9, 10, 10);
            g2.setColor(c.darker());
            g2.drawOval(x, y-9, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(cat, x+14, y);
        }
    }

    private void drawModeHint(Graphics2D g2) {
        String[] hints={
            "Click to select · Drag to pan · Scroll to zoom",
            "Click to add vertices · Double-click to close polygon",
            "Click to set query location",
            "Click 4 points to define 2 line segments"
        };
        g2.setFont(new Font("Arial",Font.ITALIC,11));
        g2.setColor(new Color(255,255,255,160));
        g2.drawString(hints[mode],11,getHeight()-19);
        g2.setColor(new Color(0,0,0,120));
        g2.drawString(hints[mode],10,getHeight()-20);
    }

    /** Attribution required by CartoDB ToS and OSM tile usage policy. */
    private void drawAttribution(Graphics2D g2) {
        if (!showBasemap||!crsReady) return;
        String attr="© "+tileProvider.getServer().displayName+" | © OpenStreetMap contributors";
        g2.setFont(new Font("Arial",Font.PLAIN,9));
        FontMetrics fm=g2.getFontMetrics();
        int aw=fm.stringWidth(attr);
        g2.setColor(new Color(255,255,255,185));
        g2.fillRect(getWidth()-aw-7,getHeight()-14,aw+6,13);
        g2.setColor(new Color(50,50,50));
        g2.drawString(attr,getWidth()-aw-4,getHeight()-4);
    }

    // ── mouse events ──────────────────────────────────────────────────────────

    @Override public void mousePressed(MouseEvent e) {
        dragStartX=e.getX(); dragStartY=e.getY(); dragging=false;
    }
    @Override public void mouseReleased(MouseEvent e) {
        if (!dragging) handleClick(e); dragging=false;
    }
    @Override public void mouseDragged(MouseEvent e) {
        offsetX += e.getX()-dragStartX;
        offsetY -= e.getY()-dragStartY;   // north is up → invert Y
        dragStartX=e.getX(); dragStartY=e.getY();
        dragging=true;
        repaint();
    }
    @Override public void mouseWheelMoved(MouseWheelEvent e) {
        double factor=(e.getWheelRotation()<0)?1.15:0.87;
        double mE=toDataE(e.getX()), mN=toDataN(e.getY());
        scale*=factor;
        offsetX = e.getX()                  - (mE-originE)*scale;
        offsetY = (getHeight()-e.getY())     - (mN-originN)*scale;
        repaint();
    }
    @Override public void mouseMoved(MouseEvent e)   {}
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e)  {}

    private void handleClick(MouseEvent e) {
        double clickE=toDataE(e.getX()), clickN=toDataN(e.getY());
        switch (mode) {
            case MODE_SELECT:
                double thresh=15.0/scale; selectedIndex=-1; double best=Double.MAX_VALUE;
                for (int i=0;i<points.size();i++){
                    double d=SpatialAlgorithms.distance(clickE,clickN,
                            points.get(i).getEasting(),points.get(i).getNorthing());
                    if (d<best&&d<thresh){best=d;selectedIndex=i;}
                }
                repaint(); firePropertyChange("selectedPoint",-2,selectedIndex);
                break;
            case MODE_POLYGON:
                if (e.getClickCount()==2&&userPolygon.size()>=3){
                    polygonClosed=true; firePropertyChange("polygonClosed",false,true);
                } else { userPolygon.add(new double[]{clickE,clickN}); }
                repaint(); break;
            case MODE_QUERY:
                queryPoint=new double[]{clickE,clickN}; repaint();
                firePropertyChange("queryPoint",null,queryPoint); break;
            case MODE_LINE:
                if (linePoints.size()>=4) linePoints.clear();
                linePoints.add(new double[]{clickE,clickN}); repaint();
                if (linePoints.size()==4) firePropertyChange("lineDefined",false,true);
                break;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Color categoryColor(String cat) {
        if (cat == null) return CAT_COLORS[CAT_COLORS.length-1];
        String upper = cat.trim().toUpperCase();
        for (int i = 0; i < CAT_NAMES.length; i++)
            if (CAT_NAMES[i].equalsIgnoreCase(upper)) return CAT_COLORS[i];
        // Hash-based fallback so unknown categories still get distinct colours
        int h = Math.abs(upper.hashCode()) % 360;
        return Color.getHSBColor(h / 360f, 0.75f, 0.88f);
    }
}

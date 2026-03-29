package hkspatialanalysis;

import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Async tile map provider for the HK Spatial Analysis basemap.
 *
 * Fetches 256×256 PNG map tiles from free public tile APIs (no API key required)
 * and caches them in an LRU memory cache. Tiles are downloaded on a background
 * thread pool; the supplied Runnable callback is invoked on the EDT when each
 * tile arrives so the panel can repaint.
 *
 * Supported tile servers (all free, no registration required):
 *   CARTO_LIGHT   – CartoDB Positron  (light, clean – best for data overlay)
 *   CARTO_DARK    – CartoDB Dark Matter (dark theme)
 *   CARTO_VOYAGER – CartoDB Voyager   (street-style colour)
 *   OSM           – OpenStreetMap Standard
 *
 * Tile coordinate system: Slippy Map / XYZ (Web Mercator, EPSG:3857).
 * All geographic coordinates passed here are WGS84 decimal degrees.
 *
 * Usage:
 *   TileMapProvider p = new TileMapProvider();
 *   BufferedImage img = p.getTileAsync(tx, ty, zoom, () -> panel.repaint());
 *   // img is null if not yet cached; panel.repaint() is called when ready.
 */
public class TileMapProvider {

    // ------------------------------------------------------------------
    // Tile server catalogue
    // ------------------------------------------------------------------

    public enum TileServer {
        CARTO_LIGHT   ("CartoDB Positron (Light)",
                       "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png"),
        CARTO_DARK    ("CartoDB Dark Matter",
                       "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"),
        CARTO_VOYAGER ("CartoDB Voyager",
                       "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"),
        OSM           ("OpenStreetMap Standard",
                       "https://tile.openstreetmap.org/{z}/{x}/{y}.png");

        public final String displayName;
        public final String urlTemplate;

        TileServer(String name, String url) {
            this.displayName = name;
            this.urlTemplate = url;
        }

        /** Build the tile URL for tile (x, y) at zoom z. */
        public String buildUrl(int x, int y, int z) {
            return urlTemplate
                    .replace("{z}", String.valueOf(z))
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y));
        }

        @Override public String toString() { return displayName; }
    }

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    public static final int  TILE_SIZE  = 256;    // pixels per tile side
    private static final int CACHE_SIZE = 400;    // max tiles kept in memory
    private static final int TIMEOUT_MS = 6000;   // HTTP connect + read timeout
    private static final int POOL_SIZE  = 6;      // parallel download threads

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private TileServer activeServer = TileServer.CARTO_LIGHT;

    /** LRU tile cache: key → "server/z/x/y". */
    private final Map<String, BufferedImage> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, BufferedImage>(CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
                    return size() > CACHE_SIZE;
                }
            });

    /** Tiles currently being fetched (avoid duplicate requests). */
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());

    /** Background thread pool for HTTP downloads. */
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE,
            r -> { Thread t = new Thread(r, "tile-fetch"); t.setDaemon(true); return t; });

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Switch tile server and flush the cache. */
    public void setServer(TileServer server) {
        this.activeServer = server;
        cache.clear();
        inFlight.clear();
    }

    public TileServer getServer() { return activeServer; }
    public TileServer[] getServers() { return TileServer.values(); }

    /**
     * Returns the cached tile image for (x, y, zoom) immediately, or
     * {@code null} if not yet cached — in which case a background fetch
     * is started and {@code onLoaded} will be invoked on the EDT when done.
     */
    public BufferedImage getTileAsync(int x, int y, int zoom, Runnable onLoaded) {
        String key = cacheKey(x, y, zoom);
        BufferedImage cached = cache.get(key);
        if (cached != null) return cached;

        if (inFlight.add(key)) {          // returns true only if newly inserted
            pool.submit(() -> {
                BufferedImage img = download(activeServer.buildUrl(x, y, zoom));
                if (img != null) cache.put(key, img);
                inFlight.remove(key);
                if (onLoaded != null) SwingUtilities.invokeLater(onLoaded);
            });
        }
        return null;
    }

    /** Preload a rectangular block of tiles in the background (no callback). */
    public void prefetch(int x0, int y0, int x1, int y1, int zoom) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                getTileAsync(x, y, zoom, null);
    }

    /** Discard all cached tiles (e.g. after switching server). */
    public void clearCache() { cache.clear(); }

    /** Gracefully stop background threads. */
    public void shutdown() { pool.shutdown(); }

    // ------------------------------------------------------------------
    // Tile coordinate math  (Slippy Map / Web Mercator)
    // ------------------------------------------------------------------

    /** Tile column index for a longitude at the given zoom level. */
    public static int lonToTileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    /**
     * Tile row index for a latitude at the given zoom level.
     * Tile rows increase southward (row 0 = north pole).
     */
    public static int latToTileY(double lat, int zoom) {
        double rad = Math.toRadians(lat);
        double y   = (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0;
        return (int) Math.floor(y * (1 << zoom));
    }

    /** West longitude of tile column x at the given zoom level. */
    public static double tileXToLon(int x, int zoom) {
        return x / (double) (1 << zoom) * 360.0 - 180.0;
    }

    /** North latitude of tile row y at the given zoom level. */
    public static double tileYToLat(int y, int zoom) {
        double n = Math.PI - 2.0 * Math.PI * y / (double) (1 << zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /**
     * Selects an appropriate zoom level for a given map scale.
     *
     * @param pixelsPerMetre current MapPanel scale (pixels per HK-Grid metre)
     * @param latDeg         display latitude for cos-correction (use 22.3 for HK)
     * @return zoom level clamped to [2, 19]
     */
    public static int scaleToZoom(double pixelsPerMetre, double latDeg) {
        // metres per pixel at the equator for the given zoom:
        //   mppEquator(z) = 40075017 / (256 * 2^z)
        // at latitude lat:
        //   mpp(z) = mppEquator(z) * cos(lat)
        // We want scale == 1/mpp(z):
        //   2^z = scale * 40075017 * cos(lat) / 256
        double twoZ = pixelsPerMetre * 40075017.0 * Math.cos(Math.toRadians(latDeg)) / 256.0;
        int z = (int) Math.round(Math.log(twoZ) / Math.log(2));
        return Math.max(2, Math.min(19, z));
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private String cacheKey(int x, int y, int zoom) {
        return activeServer.name() + "/" + zoom + "/" + x + "/" + y;
    }

    /** Download one tile image from a URL; returns null on any error. */
    private BufferedImage download(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            // Required by OSM ToS; polite for other servers too
            conn.setRequestProperty("User-Agent",
                    "HKSpatialAnalysis/1.0");
            conn.setRequestProperty("Accept", "image/png,image/*");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return ImageIO.read(conn.getInputStream());
            }
        } catch (Exception ignored) {
            // network error or decode error – return null gracefully
        }
        return null;
    }
}

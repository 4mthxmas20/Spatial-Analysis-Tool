package hkspatialanalysis;

import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Launches an interactive 3-D city-model viewer for Hong Kong.
 *
 * Architecture
 * ────────────
 * Serves the CesiumJS HTML page from a temporary localhost HTTP server
 * (random high port) instead of a file:// URL.  This is critical because:
 *
 *   • Chrome/Edge block cross-origin AJAX from file:// origins by default.
 *     The HK Gov 3D-Tiles API (data.map.gov.hk) will be rejected with a CORS
 *     error when the page origin is file://, making the scene black.
 *
 *   • Serving from http://localhost makes the browser treat the page as a
 *     normal web origin, so the CORS pre-flight to data.map.gov.hk succeeds.
 *
 * The server shuts itself down automatically after 30 minutes.
 *
 * 3D Tile source
 * ──────────────
 * HK Government CSDI 3D Data Service – Cesium 3D Tiles format.
 * Covers 3-D building models of the whole HKSAR.
 *   URL: https://data.map.gov.hk/api/3d-data/3dtiles/f2/tileset.json
 *
 * CesiumJS CDN: cesium.com (no Maven dependency needed).
 */
public class ThreeDViewer {

    public static final String HK_3D_TILES_URL =
        "https://data.map.gov.hk/api/3d-data/3dtiles/f2/tileset.json"
        + "?key=670fbb6714fc4deeaac60ddbb8823f61";

    /** CesiumJS version to load from CDN. 1.107 is the first version with
     *  Cesium3DTileset.fromUrl() AND a stable UrlTemplateImageryProvider API. */
    private static final String CESIUM_VERSION = "1.107";

    // ── Entry point ────────────────────────────────────────────────────────────

    /**
     * Starts a localhost HTTP server, generates and serves the CesiumJS viewer
     * page (with data points embedded), and opens it in the system browser.
     *
     * @param points  loaded spatial points (converted to WGS84 entities)
     */
    public static void launch(List<SpatialPoint> points) throws Exception {
        String entitiesJs = buildEntitiesJS(points);
        String html       = buildHtml(entitiesJs, !entitiesJs.isBlank());

        // Start local HTTP server on a random free port
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            // Serve CORS headers so Cesium's sub-requests also work
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, htmlBytes.length);
            try (var out = exchange.getResponseBody()) { out.write(htmlBytes); }
        });
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HK3D-HTTP");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        // Auto-shutdown after 30 min
        Thread killer = new Thread(() -> {
            try { Thread.sleep(30 * 60_000L); } catch (InterruptedException ignored) {}
            server.stop(0);
        }, "HK3D-Killer");
        killer.setDaemon(true);
        killer.start();

        // Open browser
        String url = "http://localhost:" + port + "/";
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new UnsupportedOperationException(
                "Cannot open browser automatically.\nOpen manually: " + url);
        }
        Desktop.getDesktop().browse(URI.create(url));
    }

    // ── HTML / CesiumJS page ──────────────────────────────────────────────────

    private static String buildHtml(String entitiesJs, boolean hasPoints) {
        // Use UrlTemplateImageryProvider (synchronous constructor, no Ion token needed,
        // no async race condition).  EllipsoidTerrainProvider avoids Ion world-terrain.
        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n"
            + "<head>\n"
            + "  <meta charset=\"utf-8\">\n"
            + "  <title>HK 3D City Model</title>\n"
            + "  <script src=\"https://cesium.com/downloads/cesiumjs/releases/"
                    + CESIUM_VERSION + "/Build/Cesium/Cesium.js\"></script>\n"
            + "  <link href=\"https://cesium.com/downloads/cesiumjs/releases/"
                    + CESIUM_VERSION + "/Build/Cesium/Widgets/widgets.css\" rel=\"stylesheet\">\n"
            + "  <style>\n"
            + "    html,body,#c{width:100%;height:100%;margin:0;padding:0;overflow:hidden;background:#1a2535}\n"
            + "    #info{position:absolute;top:8px;left:52px;background:rgba(15,25,45,.88);\n"
            + "          color:#ddeeff;font:bold 13px/1.5 Arial;padding:8px 14px;\n"
            + "          border-radius:6px;pointer-events:none;z-index:9}\n"
            + "    #info small{display:block;font-weight:normal;font-size:10px;color:#99b;margin-top:2px}\n"
            + "    #loading{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);\n"
            + "             background:rgba(15,25,45,.9);color:#adf;font:14px Arial;\n"
            + "             padding:16px 24px;border-radius:8px;z-index:10}\n"
            + "    .err{position:absolute;top:56px;left:10px;background:rgba(160,20,20,.9);\n"
            + "         color:#fff;padding:8px 14px;border-radius:5px;font:13px Arial;z-index:20}\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "<div id=\"c\"></div>\n"
            + "<div id=\"info\">HK 3D City Model\n"
            + "  <small>Source: HK Government CSDI 3D Tiles · OSM · CesiumJS " + CESIUM_VERSION + "</small>\n"
            + "</div>\n"
            + "<div id=\"loading\">⏳ Loading 3D tiles…</div>\n"
            + "<script>\n"
            + "// ── 1. Cesium Viewer — OSM imagery, no Cesium Ion token needed ──────────\n"
            + "const viewer = new Cesium.Viewer('c', {\n"
            + "  // UrlTemplateImageryProvider: synchronous, reliable, no async race\n"
            + "  imageryProvider: new Cesium.UrlTemplateImageryProvider({\n"
            + "    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',\n"
            + "    maximumLevel: 19,\n"
            + "    credit: '© OpenStreetMap contributors'\n"
            + "  }),\n"
            + "  // Flat ellipsoid terrain — no Ion account needed\n"
            + "  terrainProvider: new Cesium.EllipsoidTerrainProvider(),\n"
            + "  baseLayerPicker  : false,\n"
            + "  geocoder         : false,\n"
            + "  animation        : false,\n"
            + "  timeline         : false,\n"
            + "  homeButton       : true,\n"
            + "  sceneModePicker  : true,\n"
            + "  navigationHelpButton: true,\n"
            + "  fullscreenButton : true\n"
            + "});\n\n"
            + "// ── 2. HK Government 3D building tiles ───────────────────────────────────\n"
            + "async function loadTiles() {\n"
            + "  try {\n"
            + "    const tileset = await Cesium.Cesium3DTileset.fromUrl(\n"
            + "      '" + HK_3D_TILES_URL + "',\n"
            + "      { maximumScreenSpaceError: 16 }\n"
            + "    );\n"
            + "    viewer.scene.primitives.add(tileset);\n"
            + "    document.getElementById('loading').style.display = 'none';\n"
            + "    const hasPoints = " + (hasPoints ? "true" : "false") + ";\n"
            + "    if (!hasPoints) {\n"
            + "      // Fly to Hong Kong at ~600 m height for a street-level 3D view\n"
            + "      viewer.camera.flyTo({\n"
            + "        destination: Cesium.Cartesian3.fromDegrees(114.162, 22.302, 600),\n"
            + "        orientation: {\n"
            + "          heading: Cesium.Math.toRadians(10),\n"
            + "          pitch:   Cesium.Math.toRadians(-25),\n"
            + "          roll:    0\n"
            + "        },\n"
            + "        duration: 3\n"
            + "      });\n"
            + "    }\n"
            + "  } catch(err) {\n"
            + "    document.getElementById('loading').style.display = 'none';\n"
            + "    console.error('3D tiles error:', err);\n"
            + "    showErr('3D Tiles: ' + err.message);\n"
            + "  }\n"
            + "}\n\n"
            + "// ── 3. Data points from the Java app (HK1980→WGS84 pre-converted) ───────\n"
            + entitiesJs + "\n\n"
            + "// ── 4. Helpers ───────────────────────────────────────────────────────────\n"
            + "function showErr(msg) {\n"
            + "  const d = document.createElement('div');\n"
            + "  d.className = 'err'; d.textContent = '⚠ ' + msg;\n"
            + "  document.body.appendChild(d);\n"
            + "}\n\n"
            + "// Click entity to show info\n"
            + "viewer.selectedEntityChanged.addEventListener(e => {\n"
            + "  if (e && e.description) {\n"
            + "    viewer.infoBox.viewModel.showInfo = true;\n"
            + "  }\n"
            + "});\n\n"
            + "loadTiles();\n"
            + "</script>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // ── Data point entities ───────────────────────────────────────────────────

    private static String buildEntitiesJS(List<SpatialPoint> points) {
        if (points == null || points.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("(async function addPoints() {\n");
        sb.append("  const pts = [\n");

        int n = 0;
        for (SpatialPoint p : points) {
            try {
                double[] wgs = CRSTransformer.hk1980ToWGS84(p.getEasting(), p.getNorthing());
                double lon = wgs[1], lat = wgs[0], alt = p.getElevation();
                sb.append(String.format(
                    "    {lon:%f,lat:%f,alt:%.1f,name:'%s',cat:'%s',color:'%s'},\n",
                    lon, lat, alt,
                    escapeJs(p.getName()), escapeJs(p.getCategory()),
                    categoryHex(p.getCategory())));
                n++;
            } catch (Exception ignored) {}
        }

        if (n == 0) return "";     // all conversions failed

        sb.append("  ];\n");
        sb.append("  const pin = new Cesium.PinBuilder();\n");
        sb.append("  for (const p of pts) {\n");
        sb.append("    const c = Cesium.Color.fromCssColorString(p.color);\n");
        sb.append("    viewer.entities.add({\n");
        sb.append("      position: Cesium.Cartesian3.fromDegrees(p.lon, p.lat, p.alt + 5),\n");
        sb.append("      billboard: {\n");
        sb.append("        image: pin.fromColor(c, 28).toDataURL(),\n");
        sb.append("        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,\n");
        sb.append("        heightReference: Cesium.HeightReference.RELATIVE_TO_GROUND,\n");
        sb.append("        disableDepthTestDistance: Number.POSITIVE_INFINITY\n");
        sb.append("      },\n");
        sb.append("      label: {\n");
        sb.append("        text: p.name,\n");
        sb.append("        font: 'bold 11px Arial',\n");
        sb.append("        fillColor: Cesium.Color.WHITE,\n");
        sb.append("        outlineColor: Cesium.Color.BLACK,\n");
        sb.append("        outlineWidth: 2,\n");
        sb.append("        style: Cesium.LabelStyle.FILL_AND_OUTLINE,\n");
        sb.append("        pixelOffset: new Cesium.Cartesian2(0, -34),\n");
        sb.append("        heightReference: Cesium.HeightReference.RELATIVE_TO_GROUND,\n");
        sb.append("        disableDepthTestDistance: Number.POSITIVE_INFINITY,\n");
        sb.append("        scaleByDistance: new Cesium.NearFarScalar(200, 1.2, 3000, 0.5)\n");
        sb.append("      },\n");
        sb.append("      description: `<table style='font:12px Arial'>"
                    + "<tr><td><b>Name</b></td><td>${p.name}</td></tr>"
                    + "<tr><td><b>Category</b></td><td>${p.cat}</td></tr>"
                    + "<tr><td><b>Elevation</b></td><td>${p.alt} m</td></tr></table>`\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  viewer.zoomTo(viewer.entities);\n");
        sb.append("})();\n");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("'","\\'").replace("\n","").replace("\r","");
    }

    private static String categoryHex(String cat) {
        if (cat == null) return "#6b6b6b";
        switch (cat.toLowerCase()) {
            case "sports":     return "#dc3232";
            case "park":       return "#32b432";
            case "transport":  return "#3264dc";
            case "education":  return "#ffa500";
            case "heritage":   return "#9400d3";
            case "hospital":   return "#00c8c8";
            case "shopping":   return "#ff69b4";
            case "government": return "#8b4513";
            case "recreation": return "#ffd700";
            default:           return "#6b6b6b";
        }
    }
}

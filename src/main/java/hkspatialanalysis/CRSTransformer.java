package hkspatialanalysis;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoTools-based coordinate reference system (CRS) transformer.
 *
 * Provides:
 *   - HK1980 Grid (EPSG:2326) ↔ WGS84 geographic (EPSG:4326)
 *   - Arbitrary EPSG code transformation via GeoTools CRS.decode()
 *
 * This replaces hand-coded trigonometric approximations with the
 * authoritative IOGP datum parameters embedded in the GeoTools
 * EPSG authority database (gt-epsg-hsql).
 *
 * New functionality compared with Lab 1-8:
 *   - Uses OGC-standard CRS objects instead of hard-coded formulas
 *   - Supports any pair of EPSG-registered coordinate systems
 *   - Returns both numeric [lat, lon] and DMS-formatted string
 */
public class CRSTransformer {

    /** EPSG code for the Hong Kong 1980 Grid Projection. */
    public static final String EPSG_HK1980  = "EPSG:2326";
    /** EPSG code for the WGS84 geographic CRS (lat/lon in degrees). */
    public static final String EPSG_WGS84   = "EPSG:4326";

    // Pre-loaded transform objects (cached for performance)
    private static MathTransform hkToWgs   = null;
    private static MathTransform wgsToHk   = null;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Transforms a single point from HK1980 Grid (E, N in metres) to
     * WGS84 geographic coordinates.
     *
     * @param easting  HK Grid easting  (metres)
     * @param northing HK Grid northing (metres)
     * @return double[2] { latitude_degrees, longitude_degrees }
     */
    public static double[] hk1980ToWGS84(double easting, double northing) throws Exception {
        if (hkToWgs == null) hkToWgs = buildTransform(EPSG_HK1980, EPSG_WGS84);
        Coordinate src = new Coordinate(easting, northing);
        Coordinate dst = JTS.transform(src, new Coordinate(), hkToWgs);
        // EPSG:2326 → EPSG:4326: output order is (longitude, latitude)
        return new double[]{ dst.y, dst.x }; // return [lat, lon]
    }

    /**
     * Transforms a single point from WGS84 (lat/lon degrees) back to
     * HK1980 Grid (E, N in metres).
     *
     * @param lat latitude  in decimal degrees
     * @param lon longitude in decimal degrees
     * @return double[2] { easting_m, northing_m }
     */
    public static double[] wgs84ToHK1980(double lat, double lon) throws Exception {
        if (wgsToHk == null) wgsToHk = buildTransform(EPSG_WGS84, EPSG_HK1980);
        // EPSG:4326 axis order is (lat, lon) but GeoTools usually uses (lon, lat)
        Coordinate src = new Coordinate(lon, lat);
        Coordinate dst = JTS.transform(src, new Coordinate(), wgsToHk);
        return new double[]{ dst.x, dst.y };
    }

    /**
     * Transforms all points in a list from HK1980 Grid to WGS84 and
     * returns a parallel list of [lat, lon] pairs.
     */
    public static List<double[]> batchHK1980ToWGS84(List<SpatialPoint> points) throws Exception {
        List<double[]> results = new ArrayList<>();
        for (SpatialPoint p : points) {
            results.add(hk1980ToWGS84(p.getEasting(), p.getNorthing()));
        }
        return results;
    }

    /**
     * General-purpose transform between any two EPSG-registered CRS codes.
     *
     * @param fromEPSG source CRS code, e.g. "EPSG:2326"
     * @param toEPSG   target CRS code, e.g. "EPSG:4326"
     * @param x        first coordinate in source CRS
     * @param y        second coordinate in source CRS
     * @return double[2] { x', y' } in target CRS
     */
    public static double[] transform(String fromEPSG, String toEPSG,
                                     double x, double y) throws Exception {
        MathTransform t = buildTransform(fromEPSG, toEPSG);
        Coordinate src = new Coordinate(x, y);
        Coordinate dst = JTS.transform(src, new Coordinate(), t);
        return new double[]{ dst.x, dst.y };
    }

    /**
     * Returns a descriptive string for the named EPSG CRS.
     */
    public static String describeCRS(String epsgCode) {
        try {
            CoordinateReferenceSystem crs = CRS.decode(epsgCode, true);
            return crs.getName().getCode() + " – " + crs.toWKT().substring(0,
                   Math.min(120, crs.toWKT().length())) + "…";
        } catch (Exception e) {
            return "Unknown CRS: " + epsgCode;
        }
    }

    /**
     * Formats decimal degrees as DD°MM'SS.ss" N/S or E/W.
     */
    public static String formatDMS(double deg, boolean isLat) {
        String hemi = isLat ? (deg >= 0 ? "N" : "S") : (deg >= 0 ? "E" : "W");
        deg = Math.abs(deg);
        int d   = (int) deg;
        int m   = (int) ((deg - d) * 60);
        double s = ((deg - d) * 60 - m) * 60;
        return String.format("%d\u00b0%02d'%05.2f\"%s", d, m, s, hemi);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static MathTransform buildTransform(String from, String to) throws Exception {
        // lenient=true: allows axis-order flip if needed
        CoordinateReferenceSystem src = CRS.decode(from, true);
        CoordinateReferenceSystem tgt = CRS.decode(to,   true);
        return CRS.findMathTransform(src, tgt, true);
    }
}

package hkspatialanalysis;

import java.util.*;

/**
 * 3-D spatial algorithms for the HK Spatial Analysis System.
 *
 * All algorithms work in HK1980 Grid + elevation (metres above datum).
 * Elevation is treated as a third spatial axis (Z) alongside Easting (X)
 * and Northing (Y), enabling full 3-D geometric analysis.
 *
 * Algorithms
 * ──────────
 *  A1  distance3D          – 3-D Euclidean distance, slope gradient, vertical angle
 *  A2  kNearestNeighbours3D – K-nearest neighbours in 3-D space
 *  A3  bufferZoneSphere    – Spherical buffer zone (radius in 3-D)
 *  A4  boundingBox3D       – 3-D axis-aligned bounding box (AABB) and volume
 *  A5  idwInterpolation    – IDW elevation interpolation at a query location
 *  A6  centroid3D          – Weighted mean position including elevation
 */
public class SpatialAlgorithms3D {

    // ── A1: 3-D Distance, Slope, Vertical Angle ──────────────────────────────

    /**
     * 3-D Euclidean distance between two points including elevation.
     *
     * @return distance in metres
     */
    public static double distance3D(double e1, double n1, double z1,
                                    double e2, double n2, double z2) {
        double dE = e2 - e1, dN = n2 - n1, dZ = z2 - z1;
        return Math.sqrt(dE*dE + dN*dN + dZ*dZ);
    }

    /**
     * Horizontal (plan) distance between two points (ignores elevation).
     */
    public static double distanceHorizontal(double e1, double n1,
                                             double e2, double n2) {
        double dE = e2 - e1, dN = n2 - n1;
        return Math.sqrt(dE*dE + dN*dN);
    }

    /**
     * Slope gradient from point 1 to point 2.
     *
     * @return slope as a fraction (rise/run); multiply by 100 for percentage,
     *         or use {@link #verticalAngleDeg} for the angle form
     */
    public static double slopeGradient(double e1, double n1, double z1,
                                       double e2, double n2, double z2) {
        double horiz = distanceHorizontal(e1, n1, e2, n2);
        if (horiz < 1e-6) return 0.0;
        return (z2 - z1) / horiz;
    }

    /**
     * Vertical (elevation) angle from point 1 to point 2, in decimal degrees.
     * Positive = looking upward; negative = looking downward.
     */
    public static double verticalAngleDeg(double e1, double n1, double z1,
                                          double e2, double n2, double z2) {
        double horiz = distanceHorizontal(e1, n1, e2, n2);
        if (horiz < 1e-6) return (z2 > z1) ? 90.0 : -90.0;
        return Math.toDegrees(Math.atan2(z2 - z1, horiz));
    }

    // ── A2: 3-D K-Nearest Neighbours ─────────────────────────────────────────

    /**
     * Returns the indices of the k nearest neighbours to a 3-D query point,
     * sorted by 3-D Euclidean distance (closest first).
     *
     * @param zScale  scale factor to normalise elevation into the same unit as
     *                plan distances; use 1.0 for true 3-D, or a larger value
     *                (e.g. 10) to weight elevation more heavily
     */
    public static List<Integer> kNearestNeighbours3D(List<SpatialPoint> points,
                                                      double qE, double qN, double qZ,
                                                      int k, double zScale) {
        int n = points.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> {
            double dA = dist3DScaled(qE, qN, qZ, points.get(a), zScale);
            double dB = dist3DScaled(qE, qN, qZ, points.get(b), zScale);
            return Double.compare(dA, dB);
        });
        int count = Math.min(k, n);
        List<Integer> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(idx[i]);
        return result;
    }

    private static double dist3DScaled(double qE, double qN, double qZ,
                                        SpatialPoint p, double zScale) {
        double dE = p.getEasting()   - qE;
        double dN = p.getNorthing()  - qN;
        double dZ = (p.getElevation() - qZ) * zScale;
        return Math.sqrt(dE*dE + dN*dN + dZ*dZ);
    }

    // ── A3: Spherical Buffer Zone ─────────────────────────────────────────────

    /**
     * Returns indices of all points within a sphere of the given radius
     * centred at (centerE, centerN, centerZ).
     *
     * @param radius  sphere radius in metres
     */
    public static List<Integer> bufferZoneSphere(List<SpatialPoint> points,
                                                  double centerE, double centerN, double centerZ,
                                                  double radius) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            SpatialPoint p = points.get(i);
            if (distance3D(centerE, centerN, centerZ,
                           p.getEasting(), p.getNorthing(), p.getElevation()) <= radius) {
                result.add(i);
            }
        }
        return result;
    }

    // ── A4: 3-D Bounding Box (AABB) ───────────────────────────────────────────

    /**
     * Computes the 3-D axis-aligned bounding box for a set of points.
     *
     * @return double[6] { minE, minN, minZ, maxE, maxN, maxZ }
     */
    public static double[] boundingBox3D(List<SpatialPoint> points) {
        if (points.isEmpty()) return new double[6];
        double minE =  Double.MAX_VALUE, minN =  Double.MAX_VALUE, minZ =  Double.MAX_VALUE;
        double maxE = -Double.MAX_VALUE, maxN = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (SpatialPoint p : points) {
            minE = Math.min(minE, p.getEasting());   maxE = Math.max(maxE, p.getEasting());
            minN = Math.min(minN, p.getNorthing());  maxN = Math.max(maxN, p.getNorthing());
            minZ = Math.min(minZ, p.getElevation()); maxZ = Math.max(maxZ, p.getElevation());
        }
        return new double[]{ minE, minN, minZ, maxE, maxN, maxZ };
    }

    /**
     * Volume of the 3-D bounding box in cubic metres.
     * Width (E) × Depth (N) × Height (Z).
     */
    public static double boundingBoxVolume(double[] bb) {
        return (bb[3]-bb[0]) * (bb[4]-bb[1]) * (bb[5]-bb[2]);
    }

    /**
     * Surface area of the 3-D bounding box (six faces) in square metres.
     */
    public static double boundingBoxSurfaceArea(double[] bb) {
        double w = bb[3]-bb[0], d = bb[4]-bb[1], h = bb[5]-bb[2];
        return 2.0 * (w*d + w*h + d*h);
    }

    // ── A5: IDW Elevation Interpolation ──────────────────────────────────────

    /**
     * Inverse Distance Weighting (IDW) interpolation of elevation at (qE, qN).
     *
     * Estimates the elevation at the query location by weighting the known
     * elevations of all surrounding points inversely proportional to their
     * horizontal distance raised to the power p.
     *
     * @param points  data points with known elevation
     * @param qE      query Easting (HK1980 metres)
     * @param qN      query Northing (HK1980 metres)
     * @param power   IDW power parameter p (typically 2)
     * @return        estimated elevation in metres, or Double.NaN if impossible
     */
    public static double idwInterpolation(List<SpatialPoint> points,
                                           double qE, double qN, double power) {
        double weightedSum = 0.0, weightTotal = 0.0;
        for (SpatialPoint p : points) {
            double d = distanceHorizontal(qE, qN, p.getEasting(), p.getNorthing());
            if (d < 1e-3) return p.getElevation(); // query coincides with a known point
            double w = 1.0 / Math.pow(d, power);
            weightedSum += w * p.getElevation();
            weightTotal += w;
        }
        return (weightTotal < 1e-12) ? Double.NaN : weightedSum / weightTotal;
    }

    // ── A6: 3-D Centroid ──────────────────────────────────────────────────────

    /**
     * Computes the mean (centroid) position of a set of 3-D points.
     *
     * @return double[3] { meanE, meanN, meanZ }
     */
    public static double[] centroid3D(List<SpatialPoint> points) {
        if (points.isEmpty()) return new double[3];
        double sumE = 0, sumN = 0, sumZ = 0;
        for (SpatialPoint p : points) {
            sumE += p.getEasting();
            sumN += p.getNorthing();
            sumZ += p.getElevation();
        }
        int n = points.size();
        return new double[]{ sumE/n, sumN/n, sumZ/n };
    }

    /**
     * Returns the per-category 3-D centroids as a map from category name to
     * double[3] { meanE, meanN, meanZ }.
     */
    public static Map<String, double[]> categoryCentroid3D(List<SpatialPoint> points) {
        Map<String, List<SpatialPoint>> groups = new LinkedHashMap<>();
        for (SpatialPoint p : points)
            groups.computeIfAbsent(p.getCategory(), k -> new ArrayList<>()).add(p);
        Map<String, double[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SpatialPoint>> e : groups.entrySet())
            result.put(e.getKey(), centroid3D(e.getValue()));
        return result;
    }
}

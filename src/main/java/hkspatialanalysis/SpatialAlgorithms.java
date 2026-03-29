package hkspatialanalysis;

import java.util.*;

/**
 * Spatial algorithms for the HK Spatial Analysis System.
 *
 * Features (all new / different from Lab 1-8):
 *   Feature 1  - Join Computation   : distance + Whole Circle Bearing between two points
 *   Feature 2  - Polar Computation  : traverse from a known point
 *   Feature 3  - Polygon Area       : Shoelace formula
 *   Feature 4  - Point-in-Polygon   : ray-casting algorithm
 *   Feature 5  - Convex Hull        : Graham Scan
 *   Feature 6  - K-Nearest Neighbours: brute-force search
 *   Feature 7  - Line Intersection  : parametric segment test
 *   Feature 8  - Buffer Zone        : circle select (radius-based)
 *   Feature 9  - Minimum Bounding Rectangle (MBR)
 *   Feature 10 - Centroid of point set
 *   Feature 11 - Perpendicular distance from point to line
 *   Feature 12 - Minimum Spanning Tree (Prim's algorithm)
 */
public class SpatialAlgorithms {

    // -----------------------------------------------------------------------
    // Feature 1: Join Computation – distance and Whole Circle Bearing (WCB)
    // -----------------------------------------------------------------------

    /**
     * Calculates the Euclidean distance between two points (metres).
     */
    public static double distance(double e1, double n1, double e2, double n2) {
        double dE = e2 - e1;
        double dN = n2 - n1;
        return Math.sqrt(dE * dE + dN * dN);
    }

    /**
     * Calculates the Whole Circle Bearing (WCB) from point 1 to point 2.
     * Returns bearing in decimal degrees [0, 360).
     */
    public static double wholeCircleBearing(double e1, double n1, double e2, double n2) {
        double dE = e2 - e1;
        double dN = n2 - n1;
        double bearing = Math.toDegrees(Math.atan2(dE, dN));
        if (bearing < 0) bearing += 360.0;
        return bearing;
    }

    /**
     * Converts decimal degrees to DDD.MMSS format string.
     */
    public static String toDDMMSS(double decimalDeg) {
        int deg = (int) decimalDeg;
        double minFrac = (decimalDeg - deg) * 60.0;
        int min = (int) minFrac;
        double sec = (minFrac - min) * 60.0;
        return String.format("%d\u00b0%02d'%05.2f\"", deg, min, sec);
    }

    // -----------------------------------------------------------------------
    // Feature 2: Polar Computation – compute new point from bearing & distance
    // -----------------------------------------------------------------------

    /**
     * From a known origin, compute the new point given a WCB (degrees) and distance (metres).
     * Returns [newEasting, newNorthing].
     */
    public static double[] polarComputation(double originE, double originN,
                                            double bearingDeg, double distance) {
        double rad = Math.toRadians(bearingDeg);
        double newE = originE + distance * Math.sin(rad);
        double newN = originN + distance * Math.cos(rad);
        return new double[]{newE, newN};
    }

    // -----------------------------------------------------------------------
    // Feature 3: Polygon Area – Shoelace (Gauss) formula
    // -----------------------------------------------------------------------

    /**
     * Calculates the area of a polygon defined by ordered vertex arrays.
     * Uses the Shoelace formula. Returns area in square metres (positive value).
     */
    public static double polygonArea(double[] eastings, double[] northings) {
        int n = eastings.length;
        if (n < 3) return 0.0;
        double area = 0.0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += eastings[i] * northings[j];
            area -= eastings[j] * northings[i];
        }
        return Math.abs(area) / 2.0;
    }

    // -----------------------------------------------------------------------
    // Feature 4: Point-in-Polygon – ray-casting
    // -----------------------------------------------------------------------

    /**
     * Tests whether point (px, py) lies inside the polygon.
     * Uses an infinite horizontal ray cast to the right.
     */
    public static boolean pointInPolygon(double px, double py,
                                         double[] ex, double[] ny) {
        int n = ex.length;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if (((ny[i] > py) != (ny[j] > py)) &&
                (px < (ex[j] - ex[i]) * (py - ny[i]) / (ny[j] - ny[i]) + ex[i])) {
                inside = !inside;
            }
        }
        return inside;
    }

    // -----------------------------------------------------------------------
    // Feature 5: Convex Hull – Graham Scan
    // -----------------------------------------------------------------------

    /**
     * Computes the convex hull of a set of points.
     * Returns the indices (into the input arrays) of hull vertices in CCW order.
     */
    public static List<Integer> convexHull(double[] eastings, double[] northings) {
        int n = eastings.length;
        if (n < 3) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < n; i++) all.add(i);
            return all;
        }

        // Find pivot: lowest northing (ties: leftmost easting)
        int pivot = 0;
        for (int i = 1; i < n; i++) {
            if (northings[i] < northings[pivot] ||
               (northings[i] == northings[pivot] && eastings[i] < eastings[pivot])) {
                pivot = i;
            }
        }

        // Build index list, excluding pivot
        final int p = pivot;
        final double pe = eastings[p], pn = northings[p];
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i != pivot) indices.add(i);
        }

        // Sort by polar angle from pivot
        indices.sort((a, b) -> {
            double angleA = Math.atan2(northings[a] - pn, eastings[a] - pe);
            double angleB = Math.atan2(northings[b] - pn, eastings[b] - pe);
            if (angleA != angleB) return Double.compare(angleA, angleB);
            double dA = distance(pe, pn, eastings[a], northings[a]);
            double dB = distance(pe, pn, eastings[b], northings[b]);
            return Double.compare(dA, dB);
        });

        // Graham scan
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(pivot);
        stack.push(indices.get(0));
        for (int i = 1; i < indices.size(); i++) {
            int top = stack.peek();
            Iterator<Integer> it = stack.iterator();
            it.next();
            int second = it.next();
            while (stack.size() > 1 &&
                   cross(eastings[second], northings[second],
                         eastings[top],    northings[top],
                         eastings[indices.get(i)], northings[indices.get(i)]) <= 0) {
                stack.pop();
                top = stack.peek();
                it = stack.iterator();
                it.next();
                second = it.next();
            }
            stack.push(indices.get(i));
        }

        return new ArrayList<>(stack);
    }

    /** Cross product of vectors (O->A) and (O->B). Positive = CCW. */
    private static double cross(double ox, double oy, double ax, double ay,
                                 double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    // -----------------------------------------------------------------------
    // Feature 6: K-Nearest Neighbours
    // -----------------------------------------------------------------------

    /**
     * Returns indices of the K nearest points to query location (qE, qN).
     * Sorted nearest-first.
     */
    public static List<Integer> kNearestNeighbours(List<SpatialPoint> points,
                                                    double qE, double qN, int k) {
        int n = points.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> {
            double dA = distance(qE, qN, points.get(a).getEasting(), points.get(a).getNorthing());
            double dB = distance(qE, qN, points.get(b).getEasting(), points.get(b).getNorthing());
            return Double.compare(dA, dB);
        });
        int count = Math.min(k, n);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(idx[i]);
        return result;
    }

    // -----------------------------------------------------------------------
    // Feature 7: Line Segment Intersection
    // -----------------------------------------------------------------------

    /**
     * Tests whether segment AB intersects segment CD.
     * Returns the intersection point [E, N], or null if no intersection.
     */
    public static double[] lineSegmentIntersection(double ae, double an,
                                                    double be, double bn,
                                                    double ce, double cn,
                                                    double de, double dn) {
        double r_e = be - ae, r_n = bn - an;
        double s_e = de - ce, s_n = dn - cn;
        double denom = r_e * s_n - r_n * s_e;
        if (Math.abs(denom) < 1e-10) return null; // parallel

        double t = ((ce - ae) * s_n - (cn - an) * s_e) / denom;
        double u = ((ce - ae) * r_n - (cn - an) * r_e) / denom;

        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
            return new double[]{ae + t * r_e, an + t * r_n};
        }
        return null; // segments don't overlap
    }

    // -----------------------------------------------------------------------
    // Feature 8: Buffer Zone – points within radius
    // -----------------------------------------------------------------------

    /**
     * Returns indices of points within the given radius of (centerE, centerN).
     */
    public static List<Integer> bufferZone(List<SpatialPoint> points,
                                            double centerE, double centerN,
                                            double radius) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (distance(centerE, centerN,
                         points.get(i).getEasting(),
                         points.get(i).getNorthing()) <= radius) {
                result.add(i);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Feature 9: Minimum Bounding Rectangle (MBR)
    // -----------------------------------------------------------------------

    /**
     * Computes the axis-aligned MBR of a point set.
     * Returns [minE, minN, maxE, maxN].
     */
    public static double[] minimumBoundingRectangle(List<SpatialPoint> points) {
        if (points.isEmpty()) return new double[]{0, 0, 0, 0};
        double minE = Double.MAX_VALUE, minN = Double.MAX_VALUE;
        double maxE = -Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
        for (SpatialPoint p : points) {
            minE = Math.min(minE, p.getEasting());
            minN = Math.min(minN, p.getNorthing());
            maxE = Math.max(maxE, p.getEasting());
            maxN = Math.max(maxN, p.getNorthing());
        }
        return new double[]{minE, minN, maxE, maxN};
    }

    // -----------------------------------------------------------------------
    // Feature 10: Centroid of point set
    // -----------------------------------------------------------------------

    /**
     * Computes the arithmetic centroid (mean centre) of a list of points.
     * Returns [centroidE, centroidN].
     */
    public static double[] centroid(List<SpatialPoint> points) {
        if (points.isEmpty()) return new double[]{0, 0};
        double sumE = 0, sumN = 0;
        for (SpatialPoint p : points) {
            sumE += p.getEasting();
            sumN += p.getNorthing();
        }
        return new double[]{sumE / points.size(), sumN / points.size()};
    }

    // -----------------------------------------------------------------------
    // Feature 11: Perpendicular distance from a point to a line
    // -----------------------------------------------------------------------

    /**
     * Returns the perpendicular (shortest) distance from point P to the
     * infinite line defined by points A and B.
     */
    public static double pointToLineDistance(double pe, double pn,
                                              double ae, double an,
                                              double be, double bn) {
        double len = distance(ae, an, be, bn);
        if (len < 1e-10) return distance(pe, pn, ae, an);
        return Math.abs((be - ae) * (an - pn) - (ae - pe) * (bn - an)) / len;
    }

    // -----------------------------------------------------------------------
    // Feature 12: Minimum Spanning Tree – Prim's algorithm
    // -----------------------------------------------------------------------

    /**
     * Builds the Minimum Spanning Tree of the point set using Prim's algorithm.
     * Returns a list of int[2] arrays, each representing an edge [indexA, indexB].
     */
    public static List<int[]> minimumSpanningTree(List<SpatialPoint> points) {
        int n = points.size();
        List<int[]> edges = new ArrayList<>();
        if (n < 2) return edges;

        boolean[] inTree = new boolean[n];
        double[] minDist = new double[n];
        int[] parent = new int[n];
        Arrays.fill(minDist, Double.MAX_VALUE);
        Arrays.fill(parent, -1);

        minDist[0] = 0;
        for (int iter = 0; iter < n; iter++) {
            // Pick the vertex with the minimum key not yet in tree
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && (u == -1 || minDist[v] < minDist[u])) u = v;
            }
            inTree[u] = true;
            if (parent[u] != -1) edges.add(new int[]{parent[u], u});

            // Update keys of adjacent vertices
            for (int v = 0; v < n; v++) {
                if (!inTree[v]) {
                    double d = points.get(u).distanceTo(points.get(v));
                    if (d < minDist[v]) {
                        minDist[v] = d;
                        parent[v] = u;
                    }
                }
            }
        }
        return edges;
    }
}

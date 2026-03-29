package hkspatialanalysis;

/**
 * Represents a 3-D spatial point with geographic attributes.
 * Stores HK1980 Grid coordinates (Easting/Northing) and optional elevation (metres above datum).
 *
 * Elevation defaults to 0 when not supplied, preserving full backward-compatibility
 * with 2-D CSV data files.
 */
public class SpatialPoint {
    private String name;
    private double easting;
    private double northing;
    /** Elevation in metres above HK Chart Datum (or 0 if unknown). */
    private double elevation;
    private String category;
    private String description;

    /** Full constructor including elevation. */
    public SpatialPoint(String name, double easting, double northing, double elevation,
                        String category, String description) {
        this.name        = name;
        this.easting     = easting;
        this.northing    = northing;
        this.elevation   = elevation;
        this.category    = category;
        this.description = description;
    }

    /** Legacy 2-D constructor (elevation defaults to 0). */
    public SpatialPoint(String name, double easting, double northing,
                        String category, String description) {
        this(name, easting, northing, 0.0, category, description);
    }

    public String getName()        { return name; }
    public double getEasting()     { return easting; }
    public double getNorthing()    { return northing; }
    public double getElevation()   { return elevation; }
    public String getCategory()    { return category; }
    public String getDescription() { return description; }

    public void setName(String name)               { this.name = name; }
    public void setEasting(double easting)         { this.easting = easting; }
    public void setNorthing(double northing)       { this.northing = northing; }
    public void setElevation(double elevation)     { this.elevation = elevation; }
    public void setCategory(String category)       { this.category = category; }
    public void setDescription(String description) { this.description = description; }

    /** 2-D horizontal distance (HK Grid metres). */
    public double distanceTo(SpatialPoint other) {
        double dE = this.easting  - other.easting;
        double dN = this.northing - other.northing;
        return Math.sqrt(dE * dE + dN * dN);
    }

    /** 3-D Euclidean distance including elevation difference. */
    public double distanceTo3D(SpatialPoint other) {
        double dE = this.easting   - other.easting;
        double dN = this.northing  - other.northing;
        double dZ = this.elevation - other.elevation;
        return Math.sqrt(dE * dE + dN * dN + dZ * dZ);
    }

    @Override
    public String toString() {
        if (elevation != 0.0)
            return String.format("%s (E=%.1f, N=%.1f, Z=%.1f) [%s]",
                    name, easting, northing, elevation, category);
        return String.format("%s (E=%.1f, N=%.1f) [%s]", name, easting, northing, category);
    }
}

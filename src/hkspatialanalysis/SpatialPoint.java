package hkspatialanalysis;

/**
 * Represents a spatial point with geographic attributes.
 * Stores HK Grid coordinates (Easting/Northing) along with descriptive info.
 */
public class SpatialPoint {
    private String name;
    private double easting;
    private double northing;
    private String category;
    private String description;

    public SpatialPoint(String name, double easting, double northing, String category, String description) {
        this.name = name;
        this.easting = easting;
        this.northing = northing;
        this.category = category;
        this.description = description;
    }

    public String getName()        { return name; }
    public double getEasting()     { return easting; }
    public double getNorthing()    { return northing; }
    public String getCategory()    { return category; }
    public String getDescription() { return description; }

    public void setName(String name)               { this.name = name; }
    public void setEasting(double easting)         { this.easting = easting; }
    public void setNorthing(double northing)       { this.northing = northing; }
    public void setCategory(String category)       { this.category = category; }
    public void setDescription(String description) { this.description = description; }

    /**
     * Euclidean distance to another SpatialPoint (in metres, HK Grid).
     */
    public double distanceTo(SpatialPoint other) {
        double dE = this.easting  - other.easting;
        double dN = this.northing - other.northing;
        return Math.sqrt(dE * dE + dN * dN);
    }

    @Override
    public String toString() {
        return String.format("%s (E=%.1f, N=%.1f) [%s]", name, easting, northing, category);
    }
}

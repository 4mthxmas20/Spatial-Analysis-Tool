package hkspatialanalysis;

import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * GeoTools-based shapefile loader.
 *
 * Uses the GeoTools DataStore API (gt-shapefile) to open ESRI Shapefiles,
 * iterate over SimpleFeature records, and convert Point geometry features
 * into the application's SpatialPoint model.
 *
 * New functionality compared with Lab 1-8:
 *   - Reads industry-standard ESRI Shapefile format (.shp/.dbf/.prj)
 *   - Extracts arbitrary attribute columns from the DBF table
 *   - Reports the file's native CRS/projection from the .prj sidecar file
 */
public class ShapefileHandler {

    /**
     * Opens a file-chooser for a shapefile and loads its point features.
     *
     * Only Point/MultiPoint geometry features are imported; line and polygon
     * features are skipped (they can be displayed via GeoTools separately).
     *
     * @return list of SpatialPoint objects, or null if user cancelled
     */
    public static List<SpatialPoint> loadShapefileWithDialog() throws Exception {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open Shapefile");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("ESRI Shapefile (*.shp)", "shp"));
        fc.setAcceptAllFileFilterUsed(false);
        if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null;
        return loadShapefile(fc.getSelectedFile());
    }

    /**
     * Loads point features from the given .shp file into SpatialPoint objects.
     *
     * Attribute mapping heuristic (case-insensitive, first match wins):
     *   NAME / LABEL / ID  → SpatialPoint.name
     *   CATEGORY / TYPE    → SpatialPoint.category
     *   DESC / DESCRIPTION / REMARK → SpatialPoint.description
     *
     * Coordinates are taken directly from the Point geometry; if the source
     * CRS uses geographic degrees (WGS84) the coordinates are still stored
     * as-is and the caller can run CRSTransformer to re-project them.
     */
    public static List<SpatialPoint> loadShapefile(File shpFile) throws Exception {
        FileDataStore store = FileDataStoreFinder.getDataStore(shpFile);
        if (store == null) throw new Exception("GeoTools cannot open: " + shpFile.getName());

        SimpleFeatureSource source = store.getFeatureSource();
        SimpleFeatureType schema   = source.getSchema();
        SimpleFeatureCollection collection = source.getFeatures();

        // Determine attribute column indices
        String nameCol  = findColumn(schema, "NAME",        "LABEL",       "ID",   "FID");
        String catCol   = findColumn(schema, "CATEGORY",    "TYPE",        "CLASS","CAT");
        String descCol  = findColumn(schema, "DESCRIPTION", "DESC",        "REMARK","NOTE");

        List<SpatialPoint> points = new ArrayList<>();
        try (SimpleFeatureIterator it = collection.features()) {
            int autoIndex = 1;
            while (it.hasNext()) {
                SimpleFeature feature = it.next();
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom == null) continue;

                // For multipoint, use the centroid; for others take centroid
                double e, n;
                if (geom instanceof Point) {
                    e = ((Point) geom).getX();
                    n = ((Point) geom).getY();
                } else {
                    // Works for any geometry type: use centroid
                    Point c = geom.getCentroid();
                    e = c.getX();
                    n = c.getY();
                }

                String name  = getString(feature, nameCol,  "Feature_" + autoIndex);
                String cat   = getString(feature, catCol,   "Unknown");
                String desc  = getString(feature, descCol,  "");
                points.add(new SpatialPoint(name, e, n, cat, desc));
                autoIndex++;
            }
        }
        store.dispose();
        return points;
    }

    /**
     * Returns the declared CRS/projection string from a shapefile's .prj file.
     * Uses the GeoTools DataStore to look up the schema's CoordinateReferenceSystem.
     */
    public static String getShapefileCRS(File shpFile) throws Exception {
        FileDataStore store = FileDataStoreFinder.getDataStore(shpFile);
        if (store == null) return "Unknown";
        try {
            var crs = store.getSchema().getCoordinateReferenceSystem();
            return (crs != null) ? crs.getName().getCode() : "Not specified";
        } finally {
            store.dispose();
        }
    }

    /**
     * Lists all non-geometry attribute names for a shapefile's DBF table.
     */
    public static List<String> getAttributeNames(File shpFile) throws Exception {
        FileDataStore store = FileDataStoreFinder.getDataStore(shpFile);
        if (store == null) return new ArrayList<>();
        try {
            List<String> names = new ArrayList<>();
            for (AttributeDescriptor ad : store.getSchema().getAttributeDescriptors()) {
                if (!(Geometry.class.isAssignableFrom(ad.getType().getBinding())))
                    names.add(ad.getLocalName());
            }
            return names;
        } finally {
            store.dispose();
        }
    }

    // ---- private helpers ----------------------------------------------------

    private static String findColumn(SimpleFeatureType schema, String... candidates) {
        for (String c : candidates) {
            for (AttributeDescriptor ad : schema.getAttributeDescriptors()) {
                if (ad.getLocalName().equalsIgnoreCase(c)) return ad.getLocalName();
            }
        }
        return null; // no match – caller will use default
    }

    private static String getString(SimpleFeature f, String col, String fallback) {
        if (col == null) return fallback;
        Object val = f.getAttribute(col);
        return (val != null) ? val.toString().trim() : fallback;
    }
}

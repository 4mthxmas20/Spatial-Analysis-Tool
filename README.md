| Feature | Algorithm | Interaction |
|---------|-----------|-------------|
| 1 | Join Computation | Distance + WCB (Shoelace) | Pick 2 points from dialog |
| 2 | Polar Computation | Bearing + distance → new point | Select origin, enter bearing/dist |
| 3 | Polygon Area | Shoelace (Gauss) formula | Draw polygon on map |
| 4 | Point-in-Polygon | Ray-casting algorithm | Draw polygon → highlights inside points |
| 5 | Convex Hull | Graham Scan | Runs on all loaded points |
| 6 | K-Nearest Neighbours | Brute-force sorted distance | Set query point, enter K |
| 7 | Line Segment Intersection | Parametric segment test | Define 4 points as 2 segments |
| 8 | Buffer Zone | Circular radius filter | Set query centre + radius |
| 9 | Minimum Bounding Rectangle | Axis-aligned MBR | Runs on all points |
| 10 | Centroid | Mean-centre computation | Runs on all points |
| 11 | Point-to-Line Distance | Perpendicular projection | Select point + polygon edge |
| 12 | Minimum Spanning Tree | Prim's algorithm | Runs on all points |

exploring 3D algorithms
| ID | Name | Description |
| :--- | :--- | :--- |
| A1 | distance3D | 3D Euclidean distance + Slope gradient + Vertical angle (pitch angle) |
| A2 | kNearestNeighbours3D | 3D spatial KNN (with Z-axis scaling weight parameter) |
| A3 | bufferZoneSphere | Spherical buffer zone (3D radius, not cylindrical) |
| A4 | boundingBox3D | 3D axis-aligned bounding box (AABB) + Volume + Surface area |
| A5 | idwInterpolation | Inverse distance weighting (IDW) elevation interpolation |
| A6 | centroid3D | 3D centroid + Categorized 3D centroid statistics |

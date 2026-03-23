// Adapted from 
// https://github.com/matsim-org/matsim-code-examples/blob/dev.x/src/main/java/org/matsim/codeexamples/network/RunCreateNetworkFromOSM.java

package networkxml;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;

public class CreateNetwork {

    private static String UTMAsEpsg = "EPSG:27700";
	private static Path input = Paths.get("./data/input/city_of_london_roads.osm.pbf");
	private static Path filterShape = Paths.get("./data/input/City_of_London_boundary.shp");

	public static void main(String[] args) throws MalformedURLException {
		new CreateNetwork().create();
	}

	private void create() throws MalformedURLException {

		// choose an appropriate coordinate transformation. OSM Data is in WGS84. When working in UK,
		// EPSG:27700 as target system is a good choice
		CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(
				TransformationFactory.WGS84, UTMAsEpsg
		);

		// load the geometries of the shape file, so they can be used as a filter during network creation
		// using PreparedGeometry instead of Geometry increases speed a lot (usually)
		List<PreparedGeometry> filterGeometries = ShpGeometryUtils.loadPreparedGeometries(filterShape.toUri().toURL());

		// create an osm network reader with a filter
		SupersonicOsmNetworkReader reader = new SupersonicOsmNetworkReader.Builder()
				.setCoordinateTransformation(transformation)
				.setIncludeLinkAtCoordWithHierarchy((coord, hierarchyLevel) -> {

					// take all links which are motorway, trunk, or primary-street regardless of their location
					if (hierarchyLevel <= LinkProperties.LEVEL_PRIMARY) return true;

					// whithin the shape, take all links which are contained in the osm-file
					return ShpGeometryUtils.isCoordInPreparedGeometries(coord, filterGeometries);
				})
				.setAfterLinkCreated((link, osmTags, direction) -> {
                    Set<String> modes = new HashSet<>(link.getAllowedModes());
                    String highway = osmTags.get("highway");
                    String foot = osmTags.get("foot");
                    String sidewalk = osmTags.get("sidewalk");

                    boolean walkableHighway = "footway".equals(highway) || "pedestrian".equals(highway) || "path".equals(highway);
                    boolean residentialWalk = ("residential".equals(highway) || "service".equals(highway)) 
                                            && ("yes".equals(foot) || "yes".equals(sidewalk));
                    if (walkableHighway || residentialWalk) {
                        modes.add(TransportMode.walk);
                    }

                    // PT: allowed only on certain highway types
                    Set<String> ptHighways = Set.of("primary", "secondary", "tertiary");
                    if (highway != null && ptHighways.contains(highway)) {
                        modes.add(TransportMode.pt);
                    }
                    // if the original osm-link contains a cycleway tag, add bicycle as allowed transport mode
					// although for serious bicycle networks use OsmBicycleNetworkReader
					if (osmTags.containsKey(OsmTags.CYCLEWAY)) {
						modes.add(TransportMode.bike);
					}

                    link.setAllowedModes(modes);
				})
				.build();

		// the actual work is done in this call. Depending on the data size this may take a long time
		Network network = reader.read(input.toString());

		// clean the network to remove unconnected parts where agents might get stuck
		new NetworkCleaner().run(network);

        for (Link l : network.getLinks().values()) {
            System.out.println("Link ID: " + l.getId() + " | Allowed modes: " + l.getAllowedModes());
            // if (!l.getAllowedModes().contains(TransportMode.bike)) {
            //     System.out.println("Bike missing on link: " + l.getId());
            // }}

		// write out the network into a file
		new NetworkWriter(network).write("./data/output/col_network.xml.gz");
	    }

    }

}

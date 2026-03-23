package networkxml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.run.CheckMappedSchedulePlausibility;
import org.matsim.pt2matsim.run.Osm2MultimodalNetwork;
import org.matsim.pt2matsim.run.PublicTransitMapper;

public class CreatePTNetwork {
    private static final String INPUT_OSM      = "data/input/city_of_london_roads.osm";
    private static final String INPUT_SCHEDULE = "data/output/transitSchedule.xml";
    private static final String INPUT_NETWORK  = "data/output/col_network.xml";
    private static final String MAPPER_CONFIG  = "data/input/ptMapperConfig.xml";
    
    private static final String FILTERED_SCHEDULE     = "data/output/transitSchedule_filtered.xml";
    private static final String OUTPUT_NETWORK        = "data/output/network_mapped.xml";
    private static final String OUTPUT_SCHEDULE       = "data/output/transitSchedule_mapped.xml";
    
    private static final String CRS = "EPSG:27700";
    private static final String CRS_WGS  = "EPSG:4326";

    private static final double BBOX_BUFFER = 100.0;
    private static final double LINK_SEARCH_RADIUS = 1500.0;

    private static double xMin, xMax, yMin, yMax;

    private static OsmConverterConfigGroup.OsmWayParams buildRailwayParams() {
        OsmConverterConfigGroup.OsmWayParams params = new OsmConverterConfigGroup.OsmWayParams();
        params.setOsmKey("railway");
        params.setOsmValue("subway");
        params.setAllowedTransportModes(Set.of("subway", "rail"));
        params.setFreespeed(16.67);   // 60 km/h in m/s
        params.setFreespeedFactor(1.0);
        params.setLaneCapacity(9999);
        params.setLanes(1.0);
        params.setOneway(false);
        return params;
    }

    private static boolean isInCityOfLondon(double x, double y) {
        return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
    }

    private static double[] extractOsmBounds(String osmFile) throws Exception {
 
        // For .osm XML files — parse <bounds> tag via StAX
        if (osmFile.endsWith(".osm")) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            try (InputStream is = Files.newInputStream(Paths.get(osmFile))) {
                XMLStreamReader reader = factory.createXMLStreamReader(is);
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT
                            && "bounds".equals(reader.getLocalName())) {
                        return new double[]{
                            Double.parseDouble(reader.getAttributeValue(null, "minlat")),
                            Double.parseDouble(reader.getAttributeValue(null, "minlon")),
                            Double.parseDouble(reader.getAttributeValue(null, "maxlat")),
                            Double.parseDouble(reader.getAttributeValue(null, "maxlon")),
                        };
                    }
                }
            }
            throw new RuntimeException("No <bounds> tag found in OSM file.");
        }
 
        // For .osm.pbf files — use Geofabrik's standard bbox for City of London
        // PBF files embed bounds in the header block; parse via osmosis or
        // fall back to known Geofabrik City of London extract bounds
        if (osmFile.endsWith(".osm.pbf")) {
            System.out.println("  [INFO] PBF format detected — using Geofabrik bounds for City of London");
            // Geofabrik City of London extract bounds (WGS84)
            // Source: https://download.geofabrik.de/europe/great-britain/england/greater-london.html
            return new double[]{
                51.4860,   // minLat
                -0.1350,   // minLon
                51.5350,   //  maxLat
                -0.0650,   // maxLon
            };
        }
 
        throw new RuntimeException("Unsupported OSM file format: " + osmFile);
    }
 
    public static void main(String[] args) {
        // Convert OSM to MATSim multimodal network
        try {
            OsmConverterConfigGroup osmConfig = OsmConverterConfigGroup.createDefaultConfig();
            osmConfig.setOsmFile(INPUT_OSM);
            osmConfig.setOutputNetworkFile(INPUT_NETWORK);
            osmConfig.setOutputCoordinateSystem(CRS);
            osmConfig.setMaxLinkLength(1000.0);   // max link length in metres
            osmConfig.setKeepPaths(true);
            
            osmConfig.addParameterSet(buildRailwayParams()); 

            Osm2MultimodalNetwork.run(osmConfig);
            System.out.println("  -> network_mapped.xml written successfully\n");
 
        } catch (Exception e) {
            System.err.println("[ERROR] OSM conversion failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Extract bounding box from OSM file

        try {
            double[] wgsBbox = extractOsmBounds(INPUT_OSM);
 
            double wgsMinLat = wgsBbox[0];
            double wgsMinLon = wgsBbox[1];
            double wgsMaxLat = wgsBbox[2];
            double wgsMaxLon = wgsBbox[3];
 
            System.out.println("  OSM bounds (WGS84):");
            System.out.printf("    minLat=%.6f  minLon=%.6f%n", wgsMinLat, wgsMinLon);
            System.out.printf("    maxLat=%.6f  maxLon=%.6f%n", wgsMaxLat, wgsMaxLon);
 
            // Transform coord
            CoordinateTransformation ct = TransformationFactory
                .getCoordinateTransformation(CRS_WGS, CRS);
 
            Coord sw = ct.transform(new Coord(wgsMinLon, wgsMinLat));
            Coord ne = ct.transform(new Coord(wgsMaxLon, wgsMaxLat));
 
            // Apply buffer
            xMin = sw.getX() - BBOX_BUFFER;
            yMin = sw.getY() - BBOX_BUFFER;
            xMax = ne.getX() + BBOX_BUFFER;
            yMax = ne.getY() + BBOX_BUFFER;
 
        } catch (Exception e) {
            System.err.println("[ERROR] Bounding box extraction failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Filter schedule to bounding box
        try {
            Config config = ConfigUtils.createConfig();
            Scenario scenario = ScenarioUtils.createScenario(config);
            new TransitScheduleReader(scenario).readFile(INPUT_SCHEDULE);
            TransitSchedule schedule = scenario.getTransitSchedule();

            int totalLines  = schedule.getTransitLines().size();
            int totalStops  = schedule.getFacilities().size();

            // ── Step A: decide which routes to keep ───────────────────────
            // A route is kept if ANY of its stops falls inside the bbox
            Set<String> retainedStopIds = new HashSet<>();
            List<Id<TransitLine>> linesToRemove = new ArrayList<>();

            for (TransitLine line : schedule.getTransitLines().values()) {
                List<Id<TransitRoute>> routesToRemove = new ArrayList<>();

                for (TransitRoute route : line.getRoutes().values()) {
                    boolean hasCityStop = route.getStops().stream().anyMatch(s -> {
                        Coord c = s.getStopFacility().getCoord();
                        return isInCityOfLondon(c.getX(), c.getY());
                    });

                    if (!hasCityStop) {
                        routesToRemove.add(route.getId());
                    } else {
                        // Retain ALL stops this route references
                        route.getStops().forEach(s ->
                            retainedStopIds.add(s.getStopFacility().getId().toString())
                        );
                    }
                }

                for (Id<TransitRoute> rid : routesToRemove) {
                    line.removeRoute(line.getRoutes().get(rid));
                }

                if (line.getRoutes().isEmpty()) {
                    linesToRemove.add(line.getId());
                }
            }

            for (Id<TransitLine> lid : linesToRemove) {
                schedule.removeTransitLine(schedule.getTransitLines().get(lid));
            }

            // ── Step B: remove stops NOT referenced by any retained route ─
            List<Id<TransitStopFacility>> stopsToRemove = new ArrayList<>();
            for (TransitStopFacility stop : schedule.getFacilities().values()) {
                if (!retainedStopIds.contains(stop.getId().toString())) {
                    stopsToRemove.add(stop.getId());
                }
            }
            for (Id<TransitStopFacility> sid : stopsToRemove) {
                schedule.removeStopFacility(schedule.getFacilities().get(sid));
            }

            long routeCount = schedule.getTransitLines().values().stream()
                .mapToLong(l -> l.getRoutes().size()).sum();

            System.out.println("  -> " + totalLines + " lines before filter");
            System.out.println("  -> " + schedule.getTransitLines().size() + " lines retained");
            System.out.println("  -> " + totalStops + " stops before filter");
            System.out.println("  -> " + schedule.getFacilities().size() + " stops retained");
            System.out.println("  -> " + routeCount + " routes retained");

            new TransitScheduleWriter(schedule).writeFile(FILTERED_SCHEDULE);
            System.out.println("  -> Filtered schedule: " + FILTERED_SCHEDULE + "\n");

        } catch (Exception e) {
            System.err.println("[ERROR] Schedule filtering failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        //Remove stops with no nearby network links

        try {
            // Load network
            Config netConfig = ConfigUtils.createConfig();
            Scenario netScenario = ScenarioUtils.createScenario(netConfig);
            new MatsimNetworkReader(netScenario.getNetwork()).readFile(INPUT_NETWORK);
            Network network = netScenario.getNetwork();

            // Load filtered schedule
            Config schConfig = ConfigUtils.createConfig();
            Scenario schScenario = ScenarioUtils.createScenario(schConfig);
            new TransitScheduleReader(schScenario).readFile(FILTERED_SCHEDULE);
            TransitSchedule schedule = schScenario.getTransitSchedule();

            // ── Find stops with no nearby links ───────────────────────────
            Set<String> removedStopIds = new HashSet<>();
            List<Id<TransitStopFacility>> noLinkStops = new ArrayList<>();

            for (TransitStopFacility stop : schedule.getFacilities().values()) {
                Coord coord = stop.getCoord();

                Collection<Node> nearbyNodes = NetworkUtils.getNearestNodes(
                    network, coord, LINK_SEARCH_RADIUS
                );

                boolean hasNearbyLink = nearbyNodes.stream()
                    .flatMap(n -> n.getOutLinks().values().stream())
                    .anyMatch(link -> {
                        double dist = CoordUtils.distancePointLinesegment(
                            link.getFromNode().getCoord(),
                            link.getToNode().getCoord(),
                            coord
                        );
                        return dist <= LINK_SEARCH_RADIUS;
                    });

                if (!hasNearbyLink) {
                    noLinkStops.add(stop.getId());
                    removedStopIds.add(stop.getId().toString());
                    System.out.println("  [WARN] No nearby links: "
                        + stop.getId() + " (" + stop.getName() + ")"
                        + " @ (" + coord.getX() + ", " + coord.getY() + ")");
                }
            }

            // ── Remove stops with no links ────────────────────────────────
            for (Id<TransitStopFacility> id : noLinkStops) {
                schedule.removeStopFacility(schedule.getFacilities().get(id));
            }

            // ── Remove routes that reference any removed stop ─────────────
            List<Id<TransitLine>> emptyLines = new ArrayList<>();
            for (TransitLine line : schedule.getTransitLines().values()) {
                List<Id<TransitRoute>> emptyRoutes = new ArrayList<>();
                for (TransitRoute route : line.getRoutes().values()) {
                    boolean hasMissingStop = route.getStops().stream()
                        .anyMatch(s -> removedStopIds.contains(
                            s.getStopFacility().getId().toString()));
                    if (hasMissingStop) {
                        emptyRoutes.add(route.getId());
                    }
                }
                emptyRoutes.forEach(r -> line.removeRoute(line.getRoutes().get(r)));
                if (line.getRoutes().isEmpty()) {
                    emptyLines.add(line.getId());
                }
            }
            emptyLines.forEach(l ->
                schedule.removeTransitLine(schedule.getTransitLines().get(l)));

            long routeCount = schedule.getTransitLines().values().stream()
                .mapToLong(l -> l.getRoutes().size()).sum();

            System.out.println("\n  -> " + noLinkStops.size() + " stops removed (no nearby links)");
            System.out.println("  -> " + schedule.getFacilities().size() + " stops remaining");
            System.out.println("  -> " + schedule.getTransitLines().size() + " lines remaining");
            System.out.println("  -> " + routeCount + " routes remaining");

            // Overwrite filtered schedule with cleaned version
            new TransitScheduleWriter(schedule).writeFile(FILTERED_SCHEDULE);
            System.out.println("  -> Cleaned schedule written: " + FILTERED_SCHEDULE + "\n");

        } catch (Exception e) {
            System.err.println("[ERROR] Link proximity check failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Map cleaned schedule to network
        try {
            PublicTransitMapper.main(new String[]{ MAPPER_CONFIG });
            System.out.println("  -> transitSchedule_mapped.xml written successfully\n");
 
        } catch (Exception e) {
            System.err.println("[ERROR] Schedule mapping failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        // Validate mapped schedule
        try {
            CheckMappedSchedulePlausibility.main(new String[]{
                OUTPUT_SCHEDULE,
                OUTPUT_NETWORK,
                CRS,
                "./output/validation/"    // validation report output folder
            });
            System.out.println("  -> Validation report written to ./output/validation/\n");
        } catch (Exception e) {
            System.err.println("[WARN] Validation step failed: " + e.getMessage());
        }

    }

}

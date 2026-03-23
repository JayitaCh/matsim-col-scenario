package generateDemandfromOD;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.gis.ShapeFileReader;

// Use Matsim version 16.0-PR2778 and geotools version 29.6 (gt-opengis not available for higher versions)

class CreateDemand {

    private static final Logger logger = Logger.getLogger("CreateDemand");

	private static final String HOME_REGION = "Output Areas code"; //"Output Areas code";
	private static final String WORK_REGION = "OA of workplace code";
	private static final String TOTAL = "Count";
	// private static final String REGION_KEY = "Schluessel";
	// private static final String HOME_AND_WORK_REGION = "Wohnort gleich Arbeitsort";

	private static final int HOME_END_TIME = 9 * 60 * 60;
	private static final int WORK_END_TIME = 17 * 60 * 60;
	private static final double SCALE_FACTOR = 0.1;
	private static final GeometryFactory geometryFactory = new GeometryFactory();
	private static final CSVFormat csvFormat = CSVFormat.Builder.create()
			.setDelimiter(',')	
			.setHeader()
			.setSkipHeaderRecord(true)
			.setAllowMissingColumnNames(true)
			.setIgnoreHeaderCase(true)  // add this for safety
			.setTrim(true)              // add this to trim whitespace
			.build();

	private final Map<String, Geometry> regions;
	private final EnumeratedDistribution<Geometry> landcover;
	private final Path interRegionCommuterStatistic;
	private final Path innerRegionCommuterStatistic;
	private final Path loadWorkModes;
	private final Random random = new Random();
	private final Map<String, double[]> modeByRegion = new HashMap<>();

	private Population population;

    CreateDemand() {

		Path sampleFolder = Paths.get("./data/input/");

		this.interRegionCommuterStatistic = sampleFolder.resolve("commuters_inout_CityofLondon.csv");
		this.innerRegionCommuterStatistic = sampleFolder.resolve("commuters_within_CityofLondon.csv");
		this.loadWorkModes = sampleFolder.resolve("ModetoWork_cityofLondon_share.csv");

		// read in the shape file and store the geometries according to their region identifier stored as 'RS' in the
		// shape file
		regions = ShapeFileReader.getAllFeatures(sampleFolder.resolve("City_of_London.shp").toString()).stream()
				.collect(Collectors.toMap(feature -> (String) feature.getAttribute("OA21CD"), feature -> (Geometry) feature.getDefaultGeometry()));

		// Read in landcover data to make people stay in populated areas
		// we are using a weighted distribution by area-size, so that small areas receive less inhabitants than more
		// populated ones.
		List<Pair<Geometry, Double>> weightedGeometries = new ArrayList<>();
		for (SimpleFeature feature : ShapeFileReader.getAllFeatures(sampleFolder.resolve("CityofLondon_landcover.shp").toString())) {
			Geometry geometry = (Geometry) feature.getDefaultGeometry();
			weightedGeometries.add(new Pair<>(geometry, geometry.getArea()));
		}
		landcover = new EnumeratedDistribution<>(weightedGeometries);

		this.population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
	}

	Population getPopulation() {
		return this.population;
	}

	void create() {
		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		loadModes();
		createInterRegionCommuters();
		createInnerRegionCommuters();
		logger.info("Done.");
	}

	private void createInterRegionCommuters() {

		logger.info("Create commuters from inter regional statistic");

		// read the commuter csv file
		try (CSVParser parser = CSVParser.parse(Files.newInputStream(interRegionCommuterStatistic), StandardCharsets.UTF_8, csvFormat)) {

			String currentHomeRegion = "";

			// this will iterate over every line in the commuter statistics except the first one which contains the column headers
			for (CSVRecord record : parser) {
				if (record.get(HOME_REGION) != null && !record.get(HOME_REGION).equals("")) {
					currentHomeRegion = record.get(HOME_REGION);
					String workRegion = record.get(WORK_REGION);
					// we have to use the try parse value method here, because there are some weird values in the 'total'
					// column which we have to filter out
					int numberOfCommuters = tryParseValue(record.get(TOTAL));
					createPersons(currentHomeRegion, workRegion, numberOfCommuters);
				}
				// Not writing any else statement as the data is quite straightforward with no blank cells
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void createInnerRegionCommuters() {

		logger.info("Creating regional commuters.");
		try (CSVParser parser = CSVParser.parse(innerRegionCommuterStatistic, StandardCharsets.UTF_8, csvFormat)) {

			for (CSVRecord record : parser) {

				String region = record.get(HOME_REGION);
				String region_w = record.get(WORK_REGION);
				// some regions have appended zeros to their region code but the shape file has not. Remove them.
				if (region.endsWith("000")) {
					region = region.substring(0, region.length() - 3);
				}

				// only create inner region commuters for regions we have in our regions shape
				if (regions.containsKey(region)) {
					int numberOfCommuters = tryParseValue(record.get(TOTAL));					
							
					createPersons(region, region_w, numberOfCommuters);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void loadModes() {

		logger.info("Loading mode shares.");

		try (CSVParser parser = CSVParser.parse(loadWorkModes, StandardCharsets.UTF_8, csvFormat)) {
			for (CSVRecord record : parser) {

				String region = record.get("Output Areas Code");

				double car = Double.parseDouble(record.get("Car"));
				double pt  = Double.parseDouble(record.get("Public_transit"));
				double walk = Double.parseDouble(record.get("Walk"));
				double bike = Double.parseDouble(record.get("Bike"));
				double motorcycle = Double.parseDouble(record.get("Motorcycle"));
				double other = Double.parseDouble(record.get("Taxi"));

				modeByRegion.put(region, new double[]{car, pt, walk,bike,motorcycle,other});
			}
		} catch (IOException e) {
        	e.printStackTrace();
    	}
	}


	private int tryParseValue(String value) {

		// first remove things excel may have put into the value
		value = value.replace(",", "");

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}



	private Coord getCoordInGeometry(Geometry regrion) {

		double x, y;
		Point point;
		Geometry selectedLandcover;

		// select a landcover feature and test whether it is in the right region. If not select a another one.
		do {
			selectedLandcover = landcover.sample();
		} while (!regrion.contains(selectedLandcover));

		// if the landcover feature is in the correct region generate a random coordinate within the bounding box of the
		// landcover feature. Repeat until a coordinate is found which is actually within the landcover feature.
		do {
			Envelope envelope = selectedLandcover.getEnvelopeInternal();

			x = envelope.getMinX() + envelope.getWidth() * random.nextDouble();
			y = envelope.getMinY() + envelope.getHeight() * random.nextDouble();
			point = geometryFactory.createPoint(new Coordinate(x, y));
		} while (point == null || !selectedLandcover.contains(point));

		return new Coord(x, y);
	}

	private Person createPerson(Coord home, Coord work, String mode, String id) {

		// create a person by using the population's factory
		// The only required argument is an id
		Person person = population.getFactory().createPerson(Id.createPersonId(id));
		Plan plan = createPlan(home, work, mode);
		person.addPlan(plan);
		return person;
	}

	private void createPersons(String homeRegionKey, String workRegionKey, int numberOfPersons) {

		// if the person works or lives outside the state we will not use them
		if (!regions.containsKey(homeRegionKey) || !regions.containsKey(workRegionKey)) return;

		logger.info("Home region: " + homeRegionKey + " work region: " + workRegionKey + " number of commuters: " + numberOfPersons);

		Geometry homeRegion = regions.get(homeRegionKey);
		Geometry workRegion = regions.get(workRegionKey);

		// Convert mode name to map against the matsim enumeration
		Map<String, String> modeNameConvert = new HashMap<>();
		modeNameConvert.put("car", TransportMode.car);
		modeNameConvert.put("pt", TransportMode.pt);
		modeNameConvert.put("walk", TransportMode.walk);
		modeNameConvert.put("bike", TransportMode.bike);
		modeNameConvert.put("motorcycle", TransportMode.motorcycle);
		modeNameConvert.put("other", "other");
		
		double[] modes = modeByRegion.get(homeRegionKey);
		String[] modeNames = new String[]{"car", "pt", "walk", "bike", "motorcycle", "other"};
		int[] counts = new int[modes.length];
		int sum = 0;

		// Get Person counts for each mode
		for (int i = 0; i < modes.length; i++) {
			counts[i] = (int) Math.round(numberOfPersons * modes[i]);
			sum += counts[i];
		}
		// adjust last mode to make total exactly match
		counts[modes.length - 1] += numberOfPersons - sum;

		// create as many persons as there are commuters multiplied by the scale factor
		for (int i = 0; i < modeNames.length; i++) {
			String transportMode = modeNameConvert.get(modeNames[i]);
			for (int j = 0; j < counts[i] * SCALE_FACTOR; j++) {

				Coord home = getCoordInGeometry(homeRegion);
				Coord work = getCoordInGeometry(workRegion);
				String id = homeRegionKey + "_" + workRegionKey + "_" + modeNames[i] +"_"+ j;

				Person person = createPerson(home, work, transportMode, id);
				population.addPerson(person);
			}
		}
	}

	private Plan createPlan(Coord home, Coord work, String mode) {

		// create a plan for home and work. Note, that activity -> leg -> activity -> leg -> activity have to be inserted in the right
		// order.
		Plan plan = population.getFactory().createPlan();

		Activity homeActivityInTheMorning = population.getFactory().createActivityFromCoord("home", home);
		homeActivityInTheMorning.setEndTime(HOME_END_TIME);
		plan.addActivity(homeActivityInTheMorning);

		Leg toWork = population.getFactory().createLeg(mode);
		plan.addLeg(toWork);

		Activity workActivity = population.getFactory().createActivityFromCoord("work", work);
		workActivity.setEndTime(WORK_END_TIME);
		plan.addActivity(workActivity);

		Leg toHome = population.getFactory().createLeg(mode);
		plan.addLeg(toHome);

		Activity homeActivityInTheEvening = population.getFactory().createActivityFromCoord("home", home);
		plan.addActivity(homeActivityInTheEvening);

		return plan;
	}

}

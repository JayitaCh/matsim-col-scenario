package networkxml;

import java.util.concurrent.ExecutionException;

import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.run.Osm2MultimodalNetwork;

/**
 * This is a workflow for creating MATSim network using config files.
 * Use JDK-25 as pt2matsim was compiled in the latest Java SE
 */

public class osmworkflow {

	public static void main(String[] args) throws InterruptedException, ExecutionException {
		// Convert Network
		OsmConverterConfigGroup osmConfig = OsmConverterConfigGroup.loadConfig("D:\\MATSim\\matsim-london-bicycle\\src\\main\\resources\\net_config.xml");
		Osm2MultimodalNetwork.run(osmConfig); 		
	}
}
package generateDemandfromOD;

import java.nio.file.Paths;

import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;

public class RunCreateDemandNw {
    public static void main(String[] args) {

		CreateDemand createDemand = new CreateDemand();
		createDemand.create();
		Population result = createDemand.getPopulation();

		new PopulationWriter(result).write(Paths.get("data/output/pop.xml").toString());
    }
}

package in.stockline.simulation.concurrency;

import in.stockline.simulation.concurrency.SimulationModels.RaceInput;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class H2RaceSimulationDatabaseFactory implements RaceSimulationDatabaseFactory {
    @Override
    public RaceSimulationDatabase create(RaceInput input) {
        return new RaceSimulationDatabase(input);
    }
}

package in.stockline.simulation.concurrency;

import in.stockline.simulation.concurrency.SimulationModels.RaceInput;

@FunctionalInterface
public interface RaceSimulationDatabaseFactory {
    RaceSimulationDatabase create(RaceInput input);
}

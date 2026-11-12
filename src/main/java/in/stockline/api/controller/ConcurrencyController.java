package in.stockline.api.controller;

import in.stockline.simulation.concurrency.SimulationModels.RaceInput;
import in.stockline.simulation.concurrency.SimulationModels.RaceJob;
import in.stockline.simulation.concurrency.SimulationModels.RaceProgress;
import in.stockline.simulation.concurrency.SimulationModels.RaceResult;
import in.stockline.simulation.concurrency.RaceDemo;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/demo/race")
@PreAuthorize("hasRole('ADMIN')")
public class ConcurrencyController {
    private final RaceDemo demo;

    public ConcurrencyController(RaceDemo demo) { this.demo = demo; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public RaceResult run(@Valid @RequestBody RaceInput input) { return demo.run(input); }

    @PostMapping(value = "/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RaceJob start(@Valid @RequestBody RaceInput input) { return demo.start(input); }

    @GetMapping("/jobs/{id}")
    public RaceProgress progress(@PathVariable String id) { return demo.progress(id); }
}

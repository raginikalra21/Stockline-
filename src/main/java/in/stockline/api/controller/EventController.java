package in.stockline.api.controller;

import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.domain.model.Models.EventAttempt;
import in.stockline.domain.model.Models.EventMetrics;
import in.stockline.domain.model.Models.EventStatus;
import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.WorkerHealth;
import in.stockline.domain.exception.ApiProblem;
import in.stockline.persistence.repository.Store;
import in.stockline.events.consumer.EventProcessingRuntime;
import in.stockline.service.inventory.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@PreAuthorize("hasRole('ADMIN')")
public class EventController {
    private final Store store;
    private final EventProcessingRuntime runtime;

    public EventController(Store store, EventProcessingRuntime runtime) { this.store = store; this.runtime = runtime; }

    @GetMapping
    public List<OutboxEvent> events(@RequestParam(required = false) EventType eventType,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) java.util.UUID orderId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        validatePage(limit, offset);
        if (search != null && search.length() > 100) throw ApiProblem.invalid("Search text must be 100 characters or fewer.");
        return store.events(eventType == null ? null : eventType.name(), status == null ? null : status.name(),
            orderId, search, limit, offset);
    }

    @GetMapping("/{eventId}")
    public OutboxEvent event(@PathVariable java.util.UUID eventId) { return find(eventId); }

    @GetMapping("/{eventId}/attempts")
    public List<EventAttempt> attempts(@PathVariable java.util.UUID eventId) {
        find(eventId);
        return store.eventAttempts(eventId);
    }

    @PostMapping(value = "/{eventId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutboxEvent> retry(@PathVariable java.util.UUID eventId) {
        OutboxEvent event = find(eventId);
        if (!"DEAD".equals(event.status()) || !event.retryable())
            throw ApiProblem.conflict("EVENT_NOT_RETRYABLE", "Only retryable dead-letter events can be retried.");
        if (!store.retryDeadEvent(eventId, InventoryService.now()))
            throw ApiProblem.conflict("EVENT_STATE_CHANGED", "The event changed before it could be retried.");
        return ResponseEntity.accepted().body(find(eventId));
    }

    @GetMapping("/metrics")
    public EventMetrics metrics() { return store.eventMetrics(InventoryService.now()); }

    @GetMapping("/health")
    public WorkerHealth health() { return runtime.snapshot(); }

    private OutboxEvent find(java.util.UUID id) {
        return store.event(id).orElseThrow(() -> ApiProblem.notFound("That event does not exist."));
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0)
            throw ApiProblem.invalid("Use a limit from 1 to 100 and a non-negative offset.");
    }
}

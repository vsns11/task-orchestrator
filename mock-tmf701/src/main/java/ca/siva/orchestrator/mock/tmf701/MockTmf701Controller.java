package ca.siva.orchestrator.mock.tmf701;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process mock of the TMF-701 processFlow Management API.
 *
 * <p>Handles two types of PATCH operations:</p>
 * <ul>
 *   <li>{@code relatedEntity} — appends taskFlow references (accumulates, doesn't replace)</li>
 *   <li>{@code state} — overwrites the lifecycle state (completed / failed)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/mock/tmf701")
public class MockTmf701Controller {

    /** In-memory store keyed by processFlow ID. */
    private final Map<String, Map<String, Object>> processFlows = new ConcurrentHashMap<>();

    /**
     * Merges the incoming patch into the stored state for the given processFlow.
     *
     * <p>Special handling for {@code relatedEntity}: new entries are appended
     * to the existing list (not replaced). This matches real TMF-701 behavior
     * where each PATCH adds a new taskFlow reference to the processFlow.</p>
     */
    @PatchMapping("/processFlow/{id}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> patch(@PathVariable String id,
                                     @RequestBody Map<String, Object> patch) {
        log.info("[MOCK TMF-701] PATCH /processFlow/{} body={}", id, patch);
        processFlows.compute(id, (key, existing) -> {
            Map<String, Object> state = (existing == null) ? new ConcurrentHashMap<>() : existing;

            // relatedEntity: APPEND new entries to the existing list
            if (patch.containsKey("relatedEntity")) {
                List<Object> existingRefs = (List<Object>) state.getOrDefault("relatedEntity", new ArrayList<>());
                List<Object> newRefs = (List<Object>) patch.get("relatedEntity");
                ArrayList<Object> combined = new ArrayList<>(existingRefs);
                combined.addAll(newRefs);
                state.put("relatedEntity", combined);
            }

            // All other fields: overwrite (e.g. state=completed)
            patch.forEach((field, value) -> {
                if (!"relatedEntity".equals(field)) {
                    state.put(field, value);
                }
            });

            return state;
        });
        return processFlows.get(id);
    }

    /**
     * Returns the stored state for a processFlow, or a default stub if not found.
     */
    @GetMapping("/processFlow/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return processFlows.getOrDefault(id, Map.of("id", id, "state", "unknown"));
    }
}

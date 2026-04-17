package ca.siva.orchestrator.dag;

import ca.siva.orchestrator.config.DagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry that loads DAG definitions from YAML files at startup.
 * DAGs are resolved by {@code dagKey} when processing incoming flows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagRegistry {

    private final DagProperties props;
    private final Map<String, DagDefinition> byKey = new HashMap<>();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /**
     * Loads all DAG YAML files from the configured classpath location.
     *
     * <p>Failure policy: a bad file (parse error, missing dagKey, or IO failure)
     * is logged with its filename and skipped so healthy DAGs still load. The
     * class-level {@link #isReady()} gate allows callers / health checks to
     * detect a "zero DAGs loaded" outcome when every file failed.</p>
     */
    @PostConstruct
    public void load() {
        Resource[] files;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            files = resolver.getResources(props.location() + "*.yml");
        } catch (IOException e) {
            log.error("Could not enumerate DAG YAML files at {}: {}", props.location(), e.getMessage(), e);
            return;
        }

        for (Resource r : files) {
            String filename = Optional.ofNullable(r.getFilename()).orElse("<unknown>");
            try (InputStream in = r.getInputStream()) {
                DagDefinition def = yaml.readValue(in, DagDefinition.class);
                if (def == null || def.getDagKey() == null || def.getDagKey().isBlank()) {
                    log.error("Skipping DAG file {} — missing or blank dagKey", filename);
                    continue;
                }
                byKey.put(def.getDagKey(), def);
                log.info("Loaded DAG '{}' from {} with {} batches",
                        def.getDagKey(), filename,
                        Optional.ofNullable(def.getBatches()).map(java.util.List::size).orElse(0));
            } catch (IOException | RuntimeException e) {
                // RuntimeException covers Jackson parse errors (JsonMappingException,
                // JsonParseException) which extend IOException but we catch broadly
                // so a single malformed file never aborts the whole load.
                log.error("Failed to parse DAG file {}: {}", filename, e.getMessage(), e);
            }
        }

        if (byKey.isEmpty()) {
            log.error("No DAG definitions loaded from {} — orchestrator will reject every"
                    + " processFlow.initiated event until at least one DAG is available",
                    props.location());
        }
    }

    public Optional<DagDefinition> find(String dagKey) {
        return Optional.ofNullable(byKey.get(dagKey));
    }

    /** Returns true if at least one DAG was loaded successfully. */
    public boolean isReady() {
        return !byKey.isEmpty();
    }

    public int dagCount() {
        return byKey.size();
    }
}

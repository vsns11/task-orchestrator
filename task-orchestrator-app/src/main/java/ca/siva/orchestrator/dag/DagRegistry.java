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

    /** Loads all DAG YAML files from the configured classpath location. */
    @PostConstruct
    public void load() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] files = resolver.getResources(props.location() + "*.yml");
            for (Resource r : files) {
                try (InputStream in = r.getInputStream()) {
                    DagDefinition def = yaml.readValue(in, DagDefinition.class);
                    byKey.put(def.getDagKey(), def);
                    log.info("Loaded DAG '{}' with {} batches",
                            def.getDagKey(),
                            Optional.ofNullable(def.getBatches()).map(java.util.List::size).orElse(0));
                }
            }
        } catch (IOException e) {
            log.error("Failed to load DAG definitions from {}: {}", props.location(), e.getMessage());
        }
    }

    public Optional<DagDefinition> find(String dagKey) {
        return Optional.ofNullable(byKey.get(dagKey));
    }

}

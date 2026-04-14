package ca.siva.orchestrator.repository;

import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.BatchBarrierId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchBarrierRepository extends JpaRepository<BatchBarrier, BatchBarrierId> {}

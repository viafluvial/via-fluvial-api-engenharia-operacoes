package br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository;

import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.HealthCheckRunEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthCheckRunRepository extends JpaRepository<HealthCheckRunEntity, UUID> {
    Optional<HealthCheckRunEntity> findTopByEnvironmentOrderByStartedAtDesc(String environment);
    List<HealthCheckRunEntity> findByEnvironmentOrderByStartedAtDesc(String environment, Pageable pageable);
    void deleteByFinishedAtBefore(OffsetDateTime threshold);
}

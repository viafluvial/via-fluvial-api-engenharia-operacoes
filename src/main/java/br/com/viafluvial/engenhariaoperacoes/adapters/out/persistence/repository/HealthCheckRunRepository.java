package br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository;

import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.HealthCheckRunEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthCheckRunRepository extends JpaRepository<HealthCheckRunEntity, UUID> {
    Optional<HealthCheckRunEntity> findTopByEnvironmentOrderByStartedAtDesc(String environment);
    List<HealthCheckRunEntity> findByEnvironmentOrderByStartedAtDesc(String environment, Pageable pageable);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from HealthCheckRunEntity r where r.finishedAt is not null and r.finishedAt < :threshold")
    int deleteByFinishedAtBefore(@Param("threshold") OffsetDateTime threshold);
}

package br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository;

import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.HealthCheckResultEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthCheckResultRepository extends JpaRepository<HealthCheckResultEntity, UUID> {
    List<HealthCheckResultEntity> findByRunId(UUID runId);
    List<HealthCheckResultEntity> findByServiceKeyAndRunEnvironmentOrderByCheckedAtDesc(String serviceKey, String environment, Pageable pageable);
    List<HealthCheckResultEntity> findByServiceKeyAndRunEnvironmentAndCheckedAtAfterOrderByCheckedAtDesc(
        String serviceKey,
        String environment,
        OffsetDateTime checkedAt,
        Pageable pageable
    );
    List<HealthCheckResultEntity> findByServiceKeyAndRunEnvironmentAndRouteTypeAndCheckedAtAfterOrderByCheckedAtDesc(
        String serviceKey,
        String environment,
        String routeType,
        OffsetDateTime checkedAt,
        Pageable pageable
    );
    List<HealthCheckResultEntity> findByRunEnvironmentAndCheckedAtAfterOrderByCheckedAtDesc(
        String environment,
        OffsetDateTime checkedAt,
        Pageable pageable
    );
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from HealthCheckResultEntity r where r.checkedAt < :threshold")
    int deleteByCheckedAtBefore(@Param("threshold") OffsetDateTime threshold);
}

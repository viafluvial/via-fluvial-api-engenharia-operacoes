package br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository;

import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.FrontendRuntimeSnapshotEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrontendRuntimeSnapshotRepository extends JpaRepository<FrontendRuntimeSnapshotEntity, UUID> {
    Optional<FrontendRuntimeSnapshotEntity> findTopByEnvironmentOrderByServerTimestampDesc(String environment);
    List<FrontendRuntimeSnapshotEntity> findByEnvironmentAndServerTimestampAfterOrderByServerTimestampAsc(
        String environment,
        OffsetDateTime serverTimestamp,
        Pageable pageable
    );
}

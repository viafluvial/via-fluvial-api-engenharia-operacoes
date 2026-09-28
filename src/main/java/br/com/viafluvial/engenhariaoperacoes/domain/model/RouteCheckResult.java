package br.com.viafluvial.engenhariaoperacoes.domain.model;

import java.time.OffsetDateTime;

public record RouteCheckResult(
    RouteType routeType,
    HealthState state,
    String reasonCode,
    Integer httpStatus,
    Long latencyMs,
    String errorSummary,
    OffsetDateTime checkedAt,
    String monitoredUrl
) {}

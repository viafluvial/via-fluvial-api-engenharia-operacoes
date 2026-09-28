package br.com.viafluvial.engenhariaoperacoes.application.usecase;

import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.FrontendRuntimeSnapshotEntity;
import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.HealthCheckResultEntity;
import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity.HealthCheckRunEntity;
import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository.FrontendRuntimeSnapshotRepository;
import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository.HealthCheckResultRepository;
import br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.repository.HealthCheckRunRepository;
import br.com.viafluvial.engenhariaoperacoes.config.EngineeringProperties;
import br.com.viafluvial.engenhariaoperacoes.domain.model.DiagnosticCode;
import br.com.viafluvial.engenhariaoperacoes.domain.model.EnvironmentType;
import br.com.viafluvial.engenhariaoperacoes.domain.model.HealthState;
import br.com.viafluvial.engenhariaoperacoes.domain.model.RouteCheckResult;
import br.com.viafluvial.engenhariaoperacoes.domain.model.RouteType;
import br.com.viafluvial.engenhariaoperacoes.domain.model.ServiceDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EngineeringOperationsService {

    private static final List<String> HEALTH_PROBE_PATHS = List.of("/actuator/health", "/health", "/api/v1/actuator/health");

    private final EngineeringProperties properties;
    private final HealthCheckRunRepository runRepository;
    private final HealthCheckResultRepository resultRepository;
    private final FrontendRuntimeSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);
    private volatile OffsetDateTime collectorLastHeartbeat;
    private volatile Long collectorLastRunDurationMs;
    private volatile String collectorLastError;

    public EngineeringOperationsService(EngineeringProperties properties,
                                        HealthCheckRunRepository runRepository,
                                        HealthCheckResultRepository resultRepository,
                                        FrontendRuntimeSnapshotRepository snapshotRepository,
                                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getChecks().getTimeoutMillis()))
            .build();
    }

    @Scheduled(fixedDelayString = "${engineering.checks.interval-seconds:30}000")
    public void scheduledCheck() {
        if (!properties.getChecks().isEnabled()) {
            return;
        }
        runChecks(EnvironmentType.DSV, "SCHEDULED", "scheduler");
    }

    @Transactional
    public Map<String, Object> runChecks(EnvironmentType environment, String triggerType, String initiatedBy) {
        if (!runInProgress.compareAndSet(false, true)) {
            return Map.of("accepted", false, "message", "RUN_IN_PROGRESS");
        }

        Instant runStart = Instant.now();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HealthCheckRunEntity run = new HealthCheckRunEntity();
        run.setId(UUID.randomUUID());
        run.setEnvironment(environment.name());
        run.setStartedAt(now);
        run.setTriggerType(triggerType);
        run.setInitiatedBy(initiatedBy);
        run.setStatus("RUNNING");
        runRepository.save(run);

        try {
            List<ServiceCheckBundle> bundles = collectChecks(environment);
            for (ServiceCheckBundle bundle : bundles) {
                persistResult(run, bundle.service(), bundle.gateway(), bundle.diagnostic());
                persistResult(run, bundle.service(), bundle.direct(), bundle.diagnostic());
            }

            run.setStatus("DONE");
            run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            runRepository.save(run);
            purgeOldData();
            collectorLastRunDurationMs = Duration.between(runStart, Instant.now()).toMillis();
            collectorLastHeartbeat = OffsetDateTime.now(ZoneOffset.UTC);
            collectorLastError = null;
            return Map.of("accepted", true, "runId", run.getId(), "status", run.getStatus());
        } catch (RuntimeException exception) {
            run.setStatus("ERROR");
            run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            runRepository.save(run);
            collectorLastRunDurationMs = Duration.between(runStart, Instant.now()).toMillis();
            collectorLastHeartbeat = OffsetDateTime.now(ZoneOffset.UTC);
            collectorLastError = truncate(exception.getMessage());
            return Map.of(
                "accepted", false,
                "runId", run.getId(),
                "status", run.getStatus(),
                "message", Optional.ofNullable(collectorLastError).orElse("COLLECTION_ERROR")
            );
        } finally {
            runInProgress.set(false);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(EnvironmentType environment) {
        List<Map<String, Object>> services = listServices(environment, null, null, null);
        long up = services.stream().filter(item -> "UP".equals(item.get("status"))).count();
        long degraded = services.stream().filter(item -> "DEGRADED".equals(item.get("status"))).count();
        long down = services.stream().filter(item -> "DOWN".equals(item.get("status"))).count();
        long unknown = services.stream().filter(item -> List.of("UNKNOWN", "NOT_CONFIGURED", "DISABLED", "AUTH_REQUIRED").contains(item.get("status"))).count();
        long gatewayFailures = services.stream().filter(item -> "GATEWAY_FAILURE".equals(item.get("diagnostic"))).count();

        Optional<HealthCheckRunEntity> latestRun = runRepository.findTopByEnvironmentOrderByStartedAtDesc(environment.name());

        Map<String, Object> collection = Map.of(
            "enabled", properties.getChecks().isEnabled(),
            "intervalSeconds", properties.getChecks().getIntervalSeconds(),
            "historyRetentionDays", properties.getChecks().getHistoryRetentionDays(),
            "timeoutMillis", properties.getChecks().getTimeoutMillis(),
            "maxConcurrency", properties.getChecks().getMaxConcurrency(),
            "retryAttempts", properties.getChecks().getRetryAttempts(),
            "retryBackoffMillis", properties.getChecks().getRetryBackoffMillis(),
            "slo", Map.of(
                "p95LatencyWarnMs", properties.getChecks().getSlo().getP95LatencyWarnMs(),
                "p95LatencyCriticalMs", properties.getChecks().getSlo().getP95LatencyCriticalMs(),
                "consecutiveFailuresThreshold", properties.getChecks().getSlo().getConsecutiveFailuresThreshold(),
                "anomalyRatioPercent", properties.getChecks().getSlo().getAnomalyRatioPercent(),
                "gatewayCoverageTargetPercent", properties.getChecks().getSlo().getGatewayCoverageTargetPercent()
            )
        );

        Map<String, Object> latestRunInfo = new HashMap<>();
        latestRunInfo.put("runId", latestRun.map(item -> item.getId()).orElse(null));
        latestRunInfo.put("status", latestRun.map(item -> item.getStatus()).orElse("NO_RUN"));
        latestRunInfo.put("triggerType", latestRun.map(item -> item.getTriggerType()).orElse("NONE"));
        latestRunInfo.put("initiatedBy", latestRun.map(item -> item.getInitiatedBy()).orElse("-"));
        latestRunInfo.put("startedAt", latestRun.map(item -> item.getStartedAt()).orElse(null));
        latestRunInfo.put("finishedAt", latestRun.map(item -> item.getFinishedAt()).orElse(null));

        Map<String, Object> governance = governance(environment, services);
        List<Map<String, Object>> alerts = alerts(environment);

        Map<String, Object> response = new HashMap<>();
        response.put("environment", environment.name());
        response.put("total", services.size());
        response.put("up", up);
        response.put("degraded", degraded);
        response.put("down", down);
        response.put("unknown", unknown);
        response.put("gatewayFailures", gatewayFailures);
        response.put("directOnly", services.stream().filter(item -> "DIRECT_ONLY".equals(item.get("monitorMode"))).count());
        response.put("services", services);
        response.put("collection", collection);
        Map<String, Object> collector = new HashMap<>();
        collector.put("state", collectorLastError == null ? (runInProgress.get() ? "RUNNING" : "IDLE") : "ERROR");
        collector.put("inProgress", runInProgress.get());
        collector.put("lastHeartbeat", collectorLastHeartbeat);
        collector.put("lastRunDurationMs", collectorLastRunDurationMs);
        collector.put("lastError", Optional.ofNullable(collectorLastError).orElse(""));
        response.put("collector", collector);
        response.put("governance", governance);
        response.put("alerts", alerts);
        response.put("latestRun", latestRunInfo);
        response.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC));

        return response;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listServices(EnvironmentType environment, String status, String category, String query) {
        Map<String, ServiceDefinition> definitionByKey = new HashMap<>();
        resolveServices(environment).forEach(service -> definitionByKey.put(service.key(), service));

        Optional<HealthCheckRunEntity> run = runRepository.findTopByEnvironmentOrderByStartedAtDesc(environment.name());
        if (run.isEmpty()) {
            return List.of();
        }

        List<HealthCheckResultEntity> results = resultRepository.findByRunId(run.get().getId());
        Map<String, List<HealthCheckResultEntity>> grouped = new HashMap<>();
        for (HealthCheckResultEntity result : results) {
            grouped.computeIfAbsent(result.getServiceKey(), key -> new ArrayList<>()).add(result);
        }

        List<Map<String, Object>> response = new ArrayList<>();
        for (Map.Entry<String, List<HealthCheckResultEntity>> entry : grouped.entrySet()) {
            ServiceDefinition definition = definitionByKey.get(entry.getKey());
            if (definition == null) {
                continue;
            }

            HealthCheckResultEntity gateway = byRoute(entry.getValue(), RouteType.GATEWAY);
            HealthCheckResultEntity direct = byRoute(entry.getValue(), RouteType.DIRECT);
            HealthState statusValue = consolidate(gateway, direct);
            DiagnosticCode computedDiagnostic = computeDiagnosticFromPersisted(gateway, direct);
            String diagnostic = computedDiagnostic.name();

            Map<String, Object> item = new HashMap<>();
            item.put("serviceKey", definition.key());
            item.put("serviceName", definition.name());
            item.put("category", definition.category());
            item.put("status", statusValue.name());
            item.put("diagnostic", diagnostic);
            item.put("gateway", mapRoute(gateway, definition.gatewayBaseUrl()));
            item.put("direct", mapRoute(direct, definition.directBaseUrl()));
            item.put("directBaseUrl", Optional.ofNullable(definition.directBaseUrl()).orElse(""));
            item.put("gatewayBaseUrl", Optional.ofNullable(definition.gatewayBaseUrl()).orElse(""));
            item.put("monitorMode", definition.gatewayBaseUrl() == null || definition.gatewayBaseUrl().isBlank()
                ? "DIRECT_ONLY"
                : "DIRECT_AND_GATEWAY");
            item.put("directLatency", latencyMetrics(environment, definition.key(), RouteType.DIRECT));
            item.put("gatewayLatency", latencyMetrics(environment, definition.key(), RouteType.GATEWAY));
            item.put("dependencies", definition.dependencies());
            item.put("checkedAt", latestCheckedAt(gateway, direct));
            response.add(item);
        }

        response.sort(Comparator.comparing(item -> String.valueOf(item.get("serviceName"))));

        return response.stream()
            .filter(item -> status == null || status.isBlank() || String.valueOf(item.get("status")).equalsIgnoreCase(status))
            .filter(item -> category == null || category.isBlank() || String.valueOf(item.get("category")).equalsIgnoreCase(category))
            .filter(item -> query == null || query.isBlank() || (String.valueOf(item.get("serviceName")).toLowerCase().contains(query.toLowerCase())
                || String.valueOf(item.get("serviceKey")).toLowerCase().contains(query.toLowerCase())))
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> serviceDetails(EnvironmentType environment, String serviceKey) {
        List<Map<String, Object>> services = listServices(environment, null, null, serviceKey);
        return services.stream()
            .filter(item -> serviceKey.equals(item.get("serviceKey")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("SERVICE_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> serviceHistory(EnvironmentType environment, String serviceKey, int size) {
        int boundedSize = Math.max(1, Math.min(size, 5000));
        return resultRepository.findByServiceKeyAndRunEnvironmentOrderByCheckedAtDesc(
            serviceKey,
            environment.name(),
            PageRequest.of(0, boundedSize)
            )
            .stream()
            .map(this::mapHistoryItem)
            .toList();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("null")
    public Map<String, Object> runDetails(@NonNull UUID runId) {
        UUID safeRunId = Objects.requireNonNull(runId, "runId must not be null");
        HealthCheckRunEntity run = runRepository.findById(safeRunId)
            .orElseThrow(() -> new IllegalArgumentException("RUN_NOT_FOUND"));
        List<HealthCheckResultEntity> results = resultRepository.findByRunId(safeRunId);
        return Map.of(
            "runId", run.getId(),
            "environment", run.getEnvironment(),
            "status", run.getStatus(),
            "triggerType", run.getTriggerType(),
            "initiatedBy", run.getInitiatedBy(),
            "startedAt", run.getStartedAt(),
            "finishedAt", run.getFinishedAt(),
            "results", results.stream().map(this::mapHistoryItem).toList()
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> alerts(EnvironmentType environment) {
        List<Map<String, Object>> services = listServices(environment, null, null, null);
        List<Map<String, Object>> alerts = new ArrayList<>();

        int warnMs = properties.getChecks().getSlo().getP95LatencyWarnMs();
        int criticalMs = properties.getChecks().getSlo().getP95LatencyCriticalMs();
        int consecutiveFailuresThreshold = Math.max(2, properties.getChecks().getSlo().getConsecutiveFailuresThreshold());

        for (Map<String, Object> service : services) {
            String serviceKey = String.valueOf(service.get("serviceKey"));
            String serviceName = String.valueOf(service.get("serviceName"));

            Map<String, Object> directLatency = asMap(service.get("directLatency"));
            Map<String, Object> gatewayLatency = asMap(service.get("gatewayLatency"));

            long directP95 = longValue(directLatency.get("p95"));
            long gatewayP95 = longValue(gatewayLatency.get("p95"));

            if (directP95 >= criticalMs || gatewayP95 >= criticalMs) {
                alerts.add(alertItem("HIGH", "SLO_P95_CRITICAL", serviceKey, serviceName,
                    "P95 acima do limite critico de " + criticalMs + "ms", OffsetDateTime.now(ZoneOffset.UTC)));
            } else if (directP95 >= warnMs || gatewayP95 >= warnMs) {
                alerts.add(alertItem("MEDIUM", "SLO_P95_WARN", serviceKey, serviceName,
                    "P95 acima do limite de alerta de " + warnMs + "ms", OffsetDateTime.now(ZoneOffset.UTC)));
            }

            if (Boolean.TRUE.equals(directLatency.get("anomaly")) || Boolean.TRUE.equals(gatewayLatency.get("anomaly"))) {
                alerts.add(alertItem("MEDIUM", "LATENCY_ANOMALY", serviceKey, serviceName,
                    "Anomalia de latencia detectada no padrao recente", OffsetDateTime.now(ZoneOffset.UTC)));
            }

            if (hasConsecutiveFailures(environment, serviceKey, consecutiveFailuresThreshold)) {
                alerts.add(alertItem("HIGH", "CONSECUTIVE_FAILURES", serviceKey, serviceName,
                    "Falhas consecutivas acima do limite configurado", OffsetDateTime.now(ZoneOffset.UTC)));
            }

            if (isGatewayRequiredForEnvironment(environment, serviceKey)
                && String.valueOf(service.get("gatewayBaseUrl")).isBlank()) {
                alerts.add(alertItem("HIGH", "GOVERNANCE_GATEWAY_REQUIRED", serviceKey, serviceName,
                    "Servico exige rota externa para este ambiente e nao esta configurada", OffsetDateTime.now(ZoneOffset.UTC)));
            }
        }

        Map<String, Object> governance = governance(environment, services);
        if (!Boolean.TRUE.equals(governance.get("coverageCompliant"))) {
            alerts.add(alertItem(
                "LOW",
                "GOVERNANCE_COVERAGE_GAP",
                "platform",
                "Plataforma",
                "Cobertura de gateway abaixo da meta configurada",
                OffsetDateTime.now(ZoneOffset.UTC)
            ));
        }

        return alerts;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> alertsPaged(EnvironmentType environment, int page, int size) {
        List<Map<String, Object>> allAlerts = alerts(environment);

        Map<String, Object> summary = Map.of(
            "high", allAlerts.stream().filter(item -> "HIGH".equals(String.valueOf(item.get("severity")))).count(),
            "medium", allAlerts.stream().filter(item -> "MEDIUM".equals(String.valueOf(item.get("severity")))).count(),
            "low", allAlerts.stream().filter(item -> "LOW".equals(String.valueOf(item.get("severity")))).count()
        );

        return paginateList(allAlerts, page, size, summary);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> sloPaged(EnvironmentType environment, int page, int size) {
        List<Map<String, Object>> rows = buildSloRows(environment);
        return paginateList(rows, page, size, Map.of());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> analytics(EnvironmentType environment, int minutes, int bucketSeconds, int top) {
        int boundedMinutes = Math.max(30, Math.min(minutes, 60 * 24 * 7));
        int boundedBucketSeconds = Math.max(30, Math.min(bucketSeconds, 3600));
        int boundedTop = Math.max(3, Math.min(top, 30));

        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(boundedMinutes);
        List<HealthCheckResultEntity> history = resultRepository.findByRunEnvironmentAndCheckedAtAfterOrderByCheckedAtDesc(
            environment.name(),
            threshold,
            PageRequest.of(0, 15000)
        );

        Map<String, ServiceDefinition> serviceByKey = new HashMap<>();
        for (ServiceDefinition service : resolveServices(environment)) {
            serviceByKey.put(service.key(), service);
        }

        List<Map<String, Object>> httpClassTrend = buildHttpClassTrend(history, boundedBucketSeconds);
        List<Map<String, Object>> availabilityHeatmap = buildAvailabilityHeatmap(history, serviceByKey, boundedBucketSeconds);
        List<Map<String, Object>> instabilityTop = buildInstabilityTop(history, serviceByKey, boundedTop);
        List<Map<String, Object>> diagnosticFunnel = buildDiagnosticFunnel(environment);
        Map<String, Object> sloBurnRate = buildSloBurnRate(history);
        List<Map<String, Object>> mttrMtbf = buildMttrMtbf(history, serviceByKey, boundedTop);
        List<Map<String, Object>> dependencyFailureCorrelation = buildDependencyFailureCorrelation(history, serviceByKey, boundedBucketSeconds, boundedTop);
        List<Map<String, Object>> latencyVolatility = buildLatencyVolatility(history, serviceByKey, boundedTop);
        List<Map<String, Object>> maintenanceOverlay = buildMaintenanceOverlay(environment, threshold, history, boundedTop);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("windowMinutes", boundedMinutes);
        response.put("bucketSeconds", boundedBucketSeconds);
        response.put("httpClassTrend", httpClassTrend);
        response.put("diagnosticFunnel", diagnosticFunnel);
        response.put("instabilityTop", instabilityTop);
        response.put("availabilityHeatmap", availabilityHeatmap);
        response.put("sloBurnRate", sloBurnRate);
        response.put("mttrMtbf", mttrMtbf);
        response.put("dependencyFailureCorrelation", dependencyFailureCorrelation);
        response.put("latencyVolatility", latencyVolatility);
        response.put("maintenanceOverlay", maintenanceOverlay);
        return response;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> serviceRollups(EnvironmentType environment, String serviceKey, int minutes, int bucketSeconds) {
        int boundedMinutes = Math.max(15, Math.min(minutes, 60 * 24 * 7));
        int boundedBucketSeconds = Math.max(30, Math.min(bucketSeconds, 3600));
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(boundedMinutes);

        List<HealthCheckResultEntity> items = resultRepository
            .findByServiceKeyAndRunEnvironmentAndCheckedAtAfterOrderByCheckedAtDesc(
                serviceKey,
                environment.name(),
                threshold,
                PageRequest.of(0, 5000)
            );

        Map<Long, List<HealthCheckResultEntity>> buckets = new HashMap<>();
        for (HealthCheckResultEntity item : items) {
            long epochSecond = item.getCheckedAt().toEpochSecond();
            long bucketEpoch = (epochSecond / boundedBucketSeconds) * boundedBucketSeconds;
            buckets.computeIfAbsent(bucketEpoch, key -> new ArrayList<>()).add(item);
        }

        List<Long> sortedBucketKeys = buckets.keySet().stream().sorted().toList();
        List<Map<String, Object>> output = new ArrayList<>();
        for (Long bucketEpoch : sortedBucketKeys) {
            List<HealthCheckResultEntity> bucketItems = buckets.get(bucketEpoch);
            List<HealthCheckResultEntity> directItems = bucketItems.stream().filter(item -> RouteType.DIRECT.name().equals(item.getRouteType())).toList();
            List<HealthCheckResultEntity> gatewayItems = bucketItems.stream().filter(item -> RouteType.GATEWAY.name().equals(item.getRouteType())).toList();

            output.add(Map.of(
                "bucketAt", OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketEpoch), ZoneOffset.UTC),
                "direct", rollupForItems(directItems),
                "gateway", rollupForItems(gatewayItems)
            ));
        }
        return output;
    }

    @Transactional
    public Map<String, Object> saveFrontendRuntimeSnapshot(Map<String, Object> request) {
        FrontendRuntimeSnapshotEntity entity = new FrontendRuntimeSnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setEnvironment(String.valueOf(request.getOrDefault("environment", "DSV")).toUpperCase());
        entity.setApplicationName(String.valueOf(request.getOrDefault("application", "via-fluvial-app")));
        entity.setTransportMode(String.valueOf(request.getOrDefault("transportMode", "BACKEND")).toUpperCase());
        entity.setRuntimeConfigPresent(Boolean.TRUE.equals(request.get("runtimeConfigPresent")));
        entity.setBuildVersion(asNullable(request.get("buildVersion")));
        entity.setBuildCommit(asNullable(request.get("buildCommit")));
        entity.setClientTimestamp(request.get("clientTimestamp") == null ? null : OffsetDateTime.parse(String.valueOf(request.get("clientTimestamp"))));
        entity.setServerTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setEnabledServiceKeys(stringifyKeys(request.get("enabledServiceKeys")));
        snapshotRepository.save(entity);

        return Map.of(
            "snapshotId", entity.getId(),
            "environment", entity.getEnvironment(),
            "serverTimestamp", entity.getServerTimestamp()
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latestFrontendRuntime(EnvironmentType environment) {
        return snapshotRepository.findTopByEnvironmentOrderByServerTimestampDesc(environment.name())
            .map(item -> Map.<String, Object>of(
                "snapshotId", item.getId(),
                "environment", item.getEnvironment(),
                "application", item.getApplicationName(),
                "transportMode", item.getTransportMode(),
                "runtimeConfigPresent", item.isRuntimeConfigPresent(),
                "buildVersion", Optional.ofNullable(item.getBuildVersion()).orElse(""),
                "buildCommit", Optional.ofNullable(item.getBuildCommit()).orElse(""),
                "enabledServiceKeys", parseKeys(item.getEnabledServiceKeys()),
                "clientTimestamp", Optional.ofNullable(item.getClientTimestamp()).orElse(null),
                "serverTimestamp", item.getServerTimestamp()
            ))
            .orElse(Map.of(
                "environment", environment.name(),
                "application", "via-fluvial-app",
                "transportMode", "UNKNOWN",
                "enabledServiceKeys", List.of()
            ));
    }

    public List<Map<String, Object>> dependencies(EnvironmentType environment) {
        return resolveServices(environment).stream().map(service -> Map.<String, Object>of(
            "serviceKey", service.key(),
            "serviceName", service.name(),
            "dependencies", service.dependencies(),
            "requiredExternalEnvironments", service.requiredExternalEnvironments(),
            "source", "CONFIG"
        )).toList();
    }

    private List<ServiceDefinition> resolveServices(EnvironmentType environment) {
        return properties.getRegistry().getServices().stream().map(item -> new ServiceDefinition(
            item.getKey(),
            item.getName(),
            item.getCategory(),
            item.isEnabled(),
            item.getLocalDirectBaseUrl(),
            item.getLocalGatewayBaseUrl(),
            Optional.ofNullable(item.getDependencies()).orElse(List.of()),
            Optional.ofNullable(item.getRequiredExternalEnvironments()).orElse(List.of())
        )).toList();
    }

    private List<ServiceCheckBundle> collectChecks(EnvironmentType environment) {
        List<ServiceDefinition> services = resolveServices(environment);
        int concurrency = Math.max(1, properties.getChecks().getMaxConcurrency());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<ServiceCheckBundle>> tasks = services.stream().map(service -> (Callable<ServiceCheckBundle>) () -> collectForService(service)).toList();
            List<Future<ServiceCheckBundle>> futures = executor.invokeAll(tasks);

            List<ServiceCheckBundle> bundles = new ArrayList<>();
            for (Future<ServiceCheckBundle> future : futures) {
                try {
                    bundles.add(future.get());
                } catch (Exception exception) {
                    throw new IllegalStateException("COLLECTOR_EXECUTION_FAILED", exception);
                }
            }
            return bundles;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("COLLECTOR_INTERRUPTED", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private ServiceCheckBundle collectForService(ServiceDefinition service) {
        if (!service.enabled()) {
            RouteCheckResult disabledGateway = new RouteCheckResult(
                RouteType.GATEWAY,
                HealthState.DISABLED,
                "SERVICE_DISABLED",
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                null
            );
            RouteCheckResult disabledDirect = new RouteCheckResult(
                RouteType.DIRECT,
                HealthState.DISABLED,
                "SERVICE_DISABLED",
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                null
            );
            return new ServiceCheckBundle(service, disabledGateway, disabledDirect, DiagnosticCode.NOT_CONFIGURED);
        }

        RouteCheckResult gateway = checkRoute(RouteType.GATEWAY, service.gatewayBaseUrl());
        RouteCheckResult direct = checkRoute(RouteType.DIRECT, service.directBaseUrl());
        DiagnosticCode diagnostic = computeDiagnostic(gateway, direct);
        return new ServiceCheckBundle(service, gateway, direct, diagnostic);
    }

    private RouteCheckResult checkRoute(RouteType routeType, String baseUrl) {
        int attempts = Math.max(1, properties.getChecks().getRetryAttempts());
        int backoffMs = Math.max(50, properties.getChecks().getRetryBackoffMillis());
        RouteCheckResult last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            last = checkRouteOnce(routeType, baseUrl);
            if (!isRetryCandidate(last) || attempt >= attempts) {
                return last;
            }

            try {
                Thread.sleep((long) backoffMs * attempt);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return new RouteCheckResult(routeType, HealthState.DOWN, "INTERRUPTED", null, null, null, OffsetDateTime.now(ZoneOffset.UTC), null);
            }
        }

        return last;
    }

    private RouteCheckResult checkRouteOnce(RouteType routeType, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new RouteCheckResult(routeType, HealthState.NOT_CONFIGURED, "NOT_CONFIGURED", null, null, null, OffsetDateTime.now(ZoneOffset.UTC), null);
        }

        RouteCheckResult authRequiredCandidate = null;

        for (String path : HEALTH_PROBE_PATHS) {
            String target = baseUrl.replaceAll("/+$", "") + path;
            try {
                long start = System.nanoTime();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofMillis(properties.getChecks().getTimeoutMillis()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    // Some services expose health on a different path; try all candidates before concluding auth is required.
                    authRequiredCandidate = new RouteCheckResult(routeType, HealthState.AUTH_REQUIRED, "AUTH_REQUIRED", response.statusCode(), latencyMs, null, OffsetDateTime.now(ZoneOffset.UTC), target);
                    continue;
                }

                if (response.statusCode() == 404) {
                    continue;
                }

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    boolean bodyUp = response.body() == null || response.body().isBlank() || response.body().contains("\"UP\"");
                    if (bodyUp) {
                        return new RouteCheckResult(routeType, HealthState.UP, "UP", response.statusCode(), latencyMs, null, OffsetDateTime.now(ZoneOffset.UTC), target);
                    }
                    return new RouteCheckResult(routeType, HealthState.DEGRADED, "PAYLOAD_NOT_UP", response.statusCode(), latencyMs, null, OffsetDateTime.now(ZoneOffset.UTC), target);
                }

                if (response.statusCode() >= 500) {
                    return new RouteCheckResult(routeType, HealthState.DOWN, "HTTP_5XX", response.statusCode(), latencyMs, truncate(response.body()), OffsetDateTime.now(ZoneOffset.UTC), target);
                }

                return new RouteCheckResult(routeType, HealthState.UNKNOWN, "HTTP_" + response.statusCode(), response.statusCode(), latencyMs, null, OffsetDateTime.now(ZoneOffset.UTC), target);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return new RouteCheckResult(routeType, HealthState.DOWN, "INTERRUPTED", null, null, null, OffsetDateTime.now(ZoneOffset.UTC), target);
            } catch (IOException | RuntimeException exception) {
                return new RouteCheckResult(routeType, HealthState.DOWN, "NETWORK_ERROR", null, null, truncate(exception.getMessage()), OffsetDateTime.now(ZoneOffset.UTC), target);
            }
        }

        if (authRequiredCandidate != null) {
            return authRequiredCandidate;
        }

        String fallbackTarget = baseUrl.replaceAll("/+$", "") + HEALTH_PROBE_PATHS.get(0);
        return new RouteCheckResult(routeType, HealthState.UNKNOWN, "HEALTH_PATH_NOT_FOUND", 404, null, null, OffsetDateTime.now(ZoneOffset.UTC), fallbackTarget);
    }

    private boolean isRetryCandidate(RouteCheckResult result) {
        if (result == null) {
            return true;
        }

        if (result.state() == HealthState.UP
            || result.state() == HealthState.AUTH_REQUIRED
            || result.state() == HealthState.NOT_CONFIGURED
            || result.state() == HealthState.DISABLED) {
            return false;
        }

        return "NETWORK_ERROR".equals(result.reasonCode())
            || "INTERRUPTED".equals(result.reasonCode())
            || "HTTP_5XX".equals(result.reasonCode());
    }

    private DiagnosticCode computeDiagnostic(RouteCheckResult gateway, RouteCheckResult direct) {
        if (gateway.state() == HealthState.NOT_CONFIGURED && direct.state() == HealthState.NOT_CONFIGURED) {
            return DiagnosticCode.NOT_CONFIGURED;
        }

        if (gateway.state() == HealthState.NOT_CONFIGURED
            && (direct.state() == HealthState.UP || direct.state() == HealthState.AUTH_REQUIRED)) {
            return DiagnosticCode.HEALTHY;
        }

        if (gateway.state() == HealthState.UP && direct.state() == HealthState.UP) {
            return DiagnosticCode.HEALTHY;
        }
        if (gateway.state() != HealthState.UP && direct.state() == HealthState.UP) {
            return DiagnosticCode.GATEWAY_FAILURE;
        }
        if (gateway.state() == HealthState.UP && direct.state() != HealthState.UP) {
            return DiagnosticCode.DIRECT_PATH_FAILURE;
        }
        return DiagnosticCode.BACKEND_OR_NETWORK_FAILURE;
    }

    private HealthState consolidate(HealthCheckResultEntity gateway, HealthCheckResultEntity direct) {
        HealthState gatewayState = gateway == null ? HealthState.NOT_CONFIGURED : HealthState.valueOf(gateway.getHealthState());
        HealthState directState = direct == null ? HealthState.NOT_CONFIGURED : HealthState.valueOf(direct.getHealthState());

        if (gatewayState == HealthState.UP || directState == HealthState.UP) {
            if (gatewayState == HealthState.DOWN || directState == HealthState.DOWN || gatewayState == HealthState.DEGRADED || directState == HealthState.DEGRADED) {
                return HealthState.DEGRADED;
            }
            return HealthState.UP;
        }

        if (gatewayState == HealthState.AUTH_REQUIRED || directState == HealthState.AUTH_REQUIRED) {
            return HealthState.AUTH_REQUIRED;
        }

        if (gatewayState == HealthState.DOWN || directState == HealthState.DOWN) {
            return HealthState.DOWN;
        }

        if (gatewayState == HealthState.DISABLED && directState == HealthState.DISABLED) {
            return HealthState.DISABLED;
        }

        if ((gatewayState == HealthState.DISABLED && directState == HealthState.NOT_CONFIGURED)
            || (directState == HealthState.DISABLED && gatewayState == HealthState.NOT_CONFIGURED)) {
            return HealthState.DISABLED;
        }

        if (gatewayState == HealthState.NOT_CONFIGURED && directState == HealthState.NOT_CONFIGURED) {
            return HealthState.NOT_CONFIGURED;
        }

        return HealthState.UNKNOWN;
    }

    private HealthCheckResultEntity byRoute(List<HealthCheckResultEntity> results, RouteType routeType) {
        return results.stream().filter(item -> routeType.name().equals(item.getRouteType())).findFirst().orElse(null);
    }

    private Map<String, Object> mapRoute(HealthCheckResultEntity result, String baseUrl) {
        List<String> candidates = monitoredCandidates(baseUrl);
        if (result == null) {
            return Map.of(
                "status", "NOT_CONFIGURED",
                "baseUrl", Optional.ofNullable(baseUrl).orElse(""),
                "monitoredCandidates", candidates,
                "monitoredUrl", candidates.isEmpty() ? "" : candidates.get(0)
            );
        }
        return Map.of(
            "status", result.getHealthState(),
            "reasonCode", result.getReasonCode(),
            "httpStatus", Optional.ofNullable(result.getHttpStatus()).orElse(0),
            "latencyMs", Optional.ofNullable(result.getLatencyMs()).orElse(0L),
            "checkedAt", result.getCheckedAt(),
            "baseUrl", Optional.ofNullable(baseUrl).orElse(""),
            "monitoredCandidates", candidates,
            "monitoredUrl", candidates.isEmpty() ? "" : candidates.get(0)
        );
    }

    private DiagnosticCode computeDiagnosticFromPersisted(HealthCheckResultEntity gateway, HealthCheckResultEntity direct) {
        HealthState gatewayState = gateway == null
            ? HealthState.NOT_CONFIGURED
            : HealthState.valueOf(gateway.getHealthState());

        HealthState directState = direct == null
            ? HealthState.NOT_CONFIGURED
            : HealthState.valueOf(direct.getHealthState());

        if (gatewayState == HealthState.NOT_CONFIGURED && directState == HealthState.NOT_CONFIGURED) {
            return DiagnosticCode.NOT_CONFIGURED;
        }

        if (gatewayState == HealthState.NOT_CONFIGURED
            && (directState == HealthState.UP || directState == HealthState.AUTH_REQUIRED)) {
            return DiagnosticCode.HEALTHY;
        }

        if (gatewayState == HealthState.UP && directState == HealthState.UP) {
            return DiagnosticCode.HEALTHY;
        }

        if (gatewayState != HealthState.UP && directState == HealthState.UP) {
            return DiagnosticCode.GATEWAY_FAILURE;
        }

        if (gatewayState == HealthState.UP && directState != HealthState.UP) {
            return DiagnosticCode.DIRECT_PATH_FAILURE;
        }

        return DiagnosticCode.BACKEND_OR_NETWORK_FAILURE;
    }

    private OffsetDateTime latestCheckedAt(HealthCheckResultEntity gateway, HealthCheckResultEntity direct) {
        if (gateway == null) {
            return direct == null ? null : direct.getCheckedAt();
        }
        if (direct == null) {
            return gateway.getCheckedAt();
        }
        return gateway.getCheckedAt().isAfter(direct.getCheckedAt()) ? gateway.getCheckedAt() : direct.getCheckedAt();
    }

    private Map<String, Object> latencyMetrics(EnvironmentType environment, String serviceKey, RouteType routeType) {
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusHours(6);
        List<HealthCheckResultEntity> history = resultRepository
            .findByServiceKeyAndRunEnvironmentAndRouteTypeAndCheckedAtAfterOrderByCheckedAtDesc(
                serviceKey,
                environment.name(),
                routeType.name(),
                threshold,
                PageRequest.of(0, 240)
            );

        List<Long> latencies = history.stream()
            .map(item -> item.getLatencyMs())
            .filter(item -> item != null && item > 0)
            .sorted()
            .toList();

        if (latencies.isEmpty()) {
            return Map.of(
                "samples", 0,
                "avg", 0,
                "p50", 0,
                "p95", 0,
                "p99", 0,
                "anomaly", false
            );
        }

        long avg = Math.round(latencies.stream().mapToLong(item -> item.longValue()).average().orElse(0));
        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long anomalyRatioPercent = Math.max(120, properties.getChecks().getSlo().getAnomalyRatioPercent());
        boolean anomaly = latencies.size() >= 6 && avg > 0 && (p95 * 100) > (avg * anomalyRatioPercent);

        return Map.of(
            "samples", latencies.size(),
            "avg", avg,
            "p50", p50,
            "p95", p95,
            "p99", p99,
            "anomaly", anomaly
        );
    }

    private Map<String, Object> mapHistoryItem(HealthCheckResultEntity item) {
        return Map.of(
            "serviceKey", item.getServiceKey(),
            "routeType", item.getRouteType(),
            "status", item.getHealthState(),
            "reasonCode", item.getReasonCode(),
            "diagnostic", Optional.ofNullable(item.getDiagnosticCode()).orElse(""),
            "httpStatus", Optional.ofNullable(item.getHttpStatus()).orElse(0),
            "latencyMs", Optional.ofNullable(item.getLatencyMs()).orElse(0L),
            "checkedAt", item.getCheckedAt(),
            "errorSummary", Optional.ofNullable(item.getErrorSummary()).orElse("")
        );
    }

    private Map<String, Object> governance(EnvironmentType environment, List<Map<String, Object>> services) {
        long enabledServices = resolveServices(environment).stream().filter(item -> item.enabled()).count();
        long servicesWithGateway = resolveServices(environment).stream()
            .filter(item -> item.enabled())
            .filter(service -> service.gatewayBaseUrl() != null && !service.gatewayBaseUrl().isBlank())
            .count();

        long coveragePercent = enabledServices == 0 ? 100 : Math.round((servicesWithGateway * 100.0) / enabledServices);
        int coverageTarget = Math.max(0, Math.min(100, properties.getChecks().getSlo().getGatewayCoverageTargetPercent()));

        List<String> requiredGatewayServices = resolveServices(environment).stream()
            .filter(service -> isGatewayRequiredForEnvironment(environment, service.key()))
            .map(item -> item.key())
            .toList();

        List<String> nonCompliantRequiredServices = resolveServices(environment).stream()
            .filter(service -> isGatewayRequiredForEnvironment(environment, service.key()))
            .filter(service -> service.gatewayBaseUrl() == null || service.gatewayBaseUrl().isBlank())
            .map(item -> item.key())
            .toList();

        boolean coverageCompliant = coveragePercent >= coverageTarget && nonCompliantRequiredServices.isEmpty();

        return Map.of(
            "gatewayCoveragePercent", coveragePercent,
            "gatewayCoverageTargetPercent", coverageTarget,
            "coverageCompliant", coverageCompliant,
            "requiredGatewayServices", requiredGatewayServices,
            "nonCompliantRequiredServices", nonCompliantRequiredServices,
            "servicesEvaluated", services.size()
        );
    }

    private List<Map<String, Object>> buildHttpClassTrend(List<HealthCheckResultEntity> history, int bucketSeconds) {
        Map<Long, long[]> buckets = new HashMap<>();
        for (HealthCheckResultEntity item : history) {
            long bucketEpoch = toBucketEpoch(item.getCheckedAt(), bucketSeconds);
            long[] counts = buckets.computeIfAbsent(bucketEpoch, key -> new long[] {0, 0, 0, 0});

            Integer httpStatus = item.getHttpStatus();
            if (httpStatus == null || httpStatus <= 0) {
                counts[3]++;
            } else if (httpStatus >= 200 && httpStatus < 300) {
                counts[0]++;
            } else if (httpStatus >= 400 && httpStatus < 500) {
                counts[1]++;
            } else if (httpStatus >= 500 && httpStatus < 600) {
                counts[2]++;
            } else {
                counts[3]++;
            }
        }

        List<Long> sortedKeys = buckets.keySet().stream().sorted().toList();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Long bucketEpoch : sortedKeys) {
            long[] counts = buckets.get(bucketEpoch);
            trend.add(Map.of(
                "bucketAt", OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketEpoch), ZoneOffset.UTC),
                "http2xx", counts[0],
                "http4xx", counts[1],
                "http5xx", counts[2],
                "httpOther", counts[3]
            ));
        }
        return trend;
    }

    private Map<String, Object> buildSloBurnRate(List<HealthCheckResultEntity> history) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime oneHour = now.minusHours(1);
        OffsetDateTime sixHours = now.minusHours(6);

        double errorRate1h = computeFailureRate(history, oneHour);
        double errorRate6h = computeFailureRate(history, sixHours);

        double errorBudgetPercent = 1.0;
        double burnRate1h = errorBudgetPercent <= 0 ? 0 : errorRate1h / errorBudgetPercent;
        double burnRate6h = errorBudgetPercent <= 0 ? 0 : errorRate6h / errorBudgetPercent;
        boolean accelerated = burnRate1h > 1.0 && burnRate1h > (burnRate6h * 1.2);

        return Map.of(
            "errorRate1h", round2(errorRate1h),
            "errorRate6h", round2(errorRate6h),
            "burnRate1h", round2(burnRate1h),
            "burnRate6h", round2(burnRate6h),
            "accelerated", accelerated,
            "errorBudgetPercent", errorBudgetPercent
        );
    }

    private double computeFailureRate(List<HealthCheckResultEntity> history, OffsetDateTime threshold) {
        long total = 0;
        long failures = 0;
        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            if (item.getCheckedAt().isBefore(threshold)) {
                continue;
            }
            total++;
            if (isFailureState(item.getHealthState())) {
                failures++;
            }
        }
        if (total == 0) {
            return 0;
        }
        return (failures * 100.0) / total;
    }

    private List<Map<String, Object>> buildMttrMtbf(List<HealthCheckResultEntity> history,
                                                    Map<String, ServiceDefinition> serviceByKey,
                                                    int top) {
        Map<String, List<HealthCheckResultEntity>> byService = new HashMap<>();
        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            byService.computeIfAbsent(item.getServiceKey(), key -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<HealthCheckResultEntity>> entry : byService.entrySet()) {
            List<HealthCheckResultEntity> items = entry.getValue().stream()
                .sorted(Comparator.comparing(HealthCheckResultEntity::getCheckedAt))
                .toList();

            List<Long> mttrMinutes = new ArrayList<>();
            List<Long> mtbfMinutes = new ArrayList<>();
            OffsetDateTime failureStart = null;
            OffsetDateTime lastRecovery = null;
            int incidents = 0;

            for (HealthCheckResultEntity item : items) {
                boolean failed = isFailureState(item.getHealthState());
                OffsetDateTime at = item.getCheckedAt();

                if (failed) {
                    if (failureStart == null) {
                        failureStart = at;
                        incidents++;
                        if (lastRecovery != null) {
                            mtbfMinutes.add(Duration.between(lastRecovery, at).toMinutes());
                        }
                    }
                } else {
                    if (failureStart != null) {
                        mttrMinutes.add(Duration.between(failureStart, at).toMinutes());
                        failureStart = null;
                        lastRecovery = at;
                    }
                }
            }

            long mttrAvg = mttrMinutes.isEmpty() ? 0 : Math.round(mttrMinutes.stream().mapToLong(Long::longValue).average().orElse(0));
            long mtbfAvg = mtbfMinutes.isEmpty() ? 0 : Math.round(mtbfMinutes.stream().mapToLong(Long::longValue).average().orElse(0));

            ServiceDefinition definition = serviceByKey.get(entry.getKey());
            rows.add(Map.of(
                "serviceKey", entry.getKey(),
                "serviceName", definition == null ? entry.getKey() : definition.name(),
                "incidents", incidents,
                "mttrMinutes", mttrAvg,
                "mtbfMinutes", mtbfAvg
            ));
        }

        return rows.stream()
            .sorted((left, right) -> Long.compare((Long) right.get("mttrMinutes"), (Long) left.get("mttrMinutes")))
            .limit(top)
            .toList();
    }

    private List<Map<String, Object>> buildDependencyFailureCorrelation(List<HealthCheckResultEntity> history,
                                                                        Map<String, ServiceDefinition> serviceByKey,
                                                                        int bucketSeconds,
                                                                        int top) {
        Map<Long, Set<String>> failedByBucket = new HashMap<>();
        Map<String, Set<Long>> failedBucketsByService = new HashMap<>();
        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            if (!isFailureState(item.getHealthState())) {
                continue;
            }
            long bucketEpoch = toBucketEpoch(item.getCheckedAt(), bucketSeconds);
            failedByBucket.computeIfAbsent(bucketEpoch, key -> new HashSet<>()).add(item.getServiceKey());
            failedBucketsByService.computeIfAbsent(item.getServiceKey(), key -> new HashSet<>()).add(bucketEpoch);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServiceDefinition service : serviceByKey.values()) {
            List<String> deps = Optional.ofNullable(service.dependencies()).orElse(Collections.emptyList());
            if (deps.isEmpty()) {
                continue;
            }

            Set<Long> serviceBuckets = failedBucketsByService.getOrDefault(service.key(), Collections.emptySet());
            long serviceFailures = serviceBuckets.size();
            if (serviceFailures == 0) {
                continue;
            }

            for (String dependency : deps) {
                Set<Long> dependencyBuckets = failedBucketsByService.getOrDefault(dependency, Collections.emptySet());
                long dependencyFailures = dependencyBuckets.size();

                long exactCoFailures = 0;
                long nearCoFailures = 0;
                for (Long serviceBucket : serviceBuckets) {
                    Set<String> exactBucketFailures = failedByBucket.getOrDefault(serviceBucket, Collections.emptySet());
                    if (exactBucketFailures.contains(dependency)) {
                        exactCoFailures++;
                    }

                    boolean nearMatch = dependencyBuckets.contains(serviceBucket)
                        || dependencyBuckets.contains(serviceBucket - bucketSeconds)
                        || dependencyBuckets.contains(serviceBucket + bucketSeconds);
                    if (nearMatch) {
                        nearCoFailures++;
                    }
                }

                long coFailures = Math.max(exactCoFailures, nearCoFailures);
                long correlationPercent = Math.round((coFailures * 100.0) / serviceFailures);
                ServiceDefinition depDef = serviceByKey.get(dependency);
                rows.add(Map.of(
                    "serviceKey", service.key(),
                    "serviceName", service.name(),
                    "dependencyKey", dependency,
                    "dependencyName", depDef == null ? dependency : depDef.name(),
                    "serviceFailures", serviceFailures,
                    "dependencyFailures", dependencyFailures,
                    "exactCoFailures", exactCoFailures,
                    "coFailures", coFailures,
                    "correlationPercent", correlationPercent
                ));
            }
        }

        List<Map<String, Object>> sortedRows = rows.stream()
            .sorted((left, right) -> {
                int byCorrelation = Long.compare((Long) right.get("correlationPercent"), (Long) left.get("correlationPercent"));
                if (byCorrelation != 0) {
                    return byCorrelation;
                }
                return Long.compare((Long) right.get("serviceFailures"), (Long) left.get("serviceFailures"));
            })
            .limit(top)
            .toList();

        if (!sortedRows.isEmpty()) {
            return sortedRows;
        }

        List<Map<String, Object>> structuralRows = new ArrayList<>();
        for (ServiceDefinition service : serviceByKey.values()) {
            List<String> deps = Optional.ofNullable(service.dependencies()).orElse(Collections.emptyList());
            for (String dependency : deps) {
                ServiceDefinition depDef = serviceByKey.get(dependency);
                structuralRows.add(Map.of(
                    "serviceKey", service.key(),
                    "serviceName", service.name(),
                    "dependencyKey", dependency,
                    "dependencyName", depDef == null ? dependency : depDef.name(),
                    "serviceFailures", 0L,
                    "dependencyFailures", 0L,
                    "exactCoFailures", 0L,
                    "coFailures", 0L,
                    "correlationPercent", 0L
                ));
            }
        }

        return structuralRows.stream()
            .limit(top)
            .toList();
    }

    private List<Map<String, Object>> buildLatencyVolatility(List<HealthCheckResultEntity> history,
                                                              Map<String, ServiceDefinition> serviceByKey,
                                                              int top) {
        Map<String, List<Long>> latenciesByService = new HashMap<>();
        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            if (item.getLatencyMs() == null || item.getLatencyMs() <= 0) {
                continue;
            }
            latenciesByService.computeIfAbsent(item.getServiceKey(), key -> new ArrayList<>()).add(item.getLatencyMs());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : latenciesByService.entrySet()) {
            List<Long> values = entry.getValue();
            if (values.size() < 6) {
                continue;
            }

            List<Long> sorted = values.stream().sorted().toList();
            long p50 = percentile(sorted, 0.50);
            long p95 = percentile(sorted, 0.95);
            double avg = values.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = 0;
            for (Long value : values) {
                double diff = value - avg;
                variance += diff * diff;
            }
            variance = variance / values.size();
            double stddev = Math.sqrt(variance);
            long jitter = Math.max(0, p95 - p50);
            double volatilityIndex = stddev + (jitter * 0.5);

            ServiceDefinition definition = serviceByKey.get(entry.getKey());
            rows.add(Map.of(
                "serviceKey", entry.getKey(),
                "serviceName", definition == null ? entry.getKey() : definition.name(),
                "samples", values.size(),
                "p50", p50,
                "p95", p95,
                "stddev", round2(stddev),
                "jitter", jitter,
                "volatilityIndex", round2(volatilityIndex)
            ));
        }

        return rows.stream()
            .sorted((left, right) -> Double.compare((Double) right.get("volatilityIndex"), (Double) left.get("volatilityIndex")))
            .limit(top)
            .toList();
    }

    private List<Map<String, Object>> buildMaintenanceOverlay(EnvironmentType environment,
                                                              OffsetDateTime threshold,
                                                              List<HealthCheckResultEntity> history,
                                                              int top) {
        List<Map<String, Object>> events = new ArrayList<>();

        List<FrontendRuntimeSnapshotEntity> snapshots = snapshotRepository
            .findByEnvironmentAndServerTimestampAfterOrderByServerTimestampAsc(
                environment.name(),
                threshold,
                PageRequest.of(0, 500)
            );

        String previousBuild = null;
        for (FrontendRuntimeSnapshotEntity snapshot : snapshots) {
            String currentBuild = Optional.ofNullable(snapshot.getBuildVersion()).orElse("")
                + "@" + Optional.ofNullable(snapshot.getBuildCommit()).orElse("");
            if (!currentBuild.equals(previousBuild)) {
                events.add(Map.of(
                    "at", snapshot.getServerTimestamp(),
                    "type", "DEPLOY",
                    "label", "Deploy frontend/runtime",
                    "details", currentBuild
                ));
                previousBuild = currentBuild;
            }
        }

        List<HealthCheckRunEntity> runs = runRepository.findByEnvironmentOrderByStartedAtDesc(environment.name(), PageRequest.of(0, 500));
        for (HealthCheckRunEntity run : runs) {
            if (run.getStartedAt() == null || run.getStartedAt().isBefore(threshold)) {
                continue;
            }
            if (!"MANUAL".equalsIgnoreCase(run.getTriggerType())) {
                continue;
            }
            events.add(Map.of(
                "at", run.getStartedAt(),
                "type", "MAINTENANCE",
                "label", "Rodada manual de verificacao",
                "details", Optional.ofNullable(run.getInitiatedBy()).orElse("-")
            ));
        }

        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            if (!isFailureState(item.getHealthState())) {
                continue;
            }
            events.add(Map.of(
                "at", item.getCheckedAt(),
                "type", "INCIDENT",
                "label", "Falha detectada",
                "details", item.getServiceKey() + " - " + item.getHealthState()
            ));
        }

        return events.stream()
            .sorted((left, right) -> String.valueOf(left.get("at")).compareTo(String.valueOf(right.get("at"))))
            .limit(Math.max(10, top * 6L))
            .toList();
    }

    private boolean isFailureState(String state) {
        return HealthState.DOWN.name().equals(state)
            || HealthState.DEGRADED.name().equals(state)
            || HealthState.UNKNOWN.name().equals(state);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<Map<String, Object>> buildAvailabilityHeatmap(List<HealthCheckResultEntity> history,
                                                                Map<String, ServiceDefinition> serviceByKey,
                                                                int bucketSeconds) {
        Map<String, Map<Long, List<HealthCheckResultEntity>>> grouped = new HashMap<>();
        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            long bucketEpoch = toBucketEpoch(item.getCheckedAt(), bucketSeconds);
            grouped
                .computeIfAbsent(item.getServiceKey(), key -> new HashMap<>())
                .computeIfAbsent(bucketEpoch, key -> new ArrayList<>())
                .add(item);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<Long, List<HealthCheckResultEntity>>> entry : grouped.entrySet()) {
            String serviceKey = entry.getKey();
            ServiceDefinition definition = serviceByKey.get(serviceKey);

            List<Long> sortedBuckets = entry.getValue().keySet().stream().sorted().toList();
            List<Map<String, Object>> points = new ArrayList<>();
            for (Long bucketEpoch : sortedBuckets) {
                List<HealthCheckResultEntity> bucketItems = entry.getValue().get(bucketEpoch);
                long up = bucketItems.stream().filter(item -> HealthState.UP.name().equals(item.getHealthState())).count();
                long total = bucketItems.size();
                long upRate = total == 0 ? 0 : Math.round((up * 100.0) / total);
                int score = upRate >= 95 ? 1 : (upRate >= 70 ? 0 : -1);

                points.add(Map.of(
                    "bucketAt", OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketEpoch), ZoneOffset.UTC),
                    "score", score,
                    "upRate", upRate
                ));
            }

            rows.add(Map.of(
                "serviceKey", serviceKey,
                "serviceName", definition == null ? serviceKey : definition.name(),
                "points", points
            ));
        }

        rows.sort(Comparator.comparing(item -> String.valueOf(item.get("serviceName"))));
        return rows;
    }

    private List<Map<String, Object>> buildInstabilityTop(List<HealthCheckResultEntity> history,
                                                           Map<String, ServiceDefinition> serviceByKey,
                                                           int top) {
        Map<String, List<HealthCheckResultEntity>> byService = new HashMap<>();
        for (HealthCheckResultEntity item : history) {
            if (!RouteType.DIRECT.name().equals(item.getRouteType())) {
                continue;
            }
            byService.computeIfAbsent(item.getServiceKey(), key -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<HealthCheckResultEntity>> entry : byService.entrySet()) {
            List<HealthCheckResultEntity> items = entry.getValue().stream()
                .sorted(Comparator.comparing(HealthCheckResultEntity::getCheckedAt))
                .toList();

            int transitions = 0;
            int failures = 0;
            String previous = null;
            for (HealthCheckResultEntity item : items) {
                String current = item.getHealthState();
                if (previous != null && !previous.equals(current)) {
                    transitions++;
                }
                if (HealthState.DOWN.name().equals(current)
                    || HealthState.DEGRADED.name().equals(current)
                    || HealthState.UNKNOWN.name().equals(current)) {
                    failures++;
                }
                previous = current;
            }

            int score = transitions * 2 + failures;
            ServiceDefinition definition = serviceByKey.get(entry.getKey());
            rows.add(Map.of(
                "serviceKey", entry.getKey(),
                "serviceName", definition == null ? entry.getKey() : definition.name(),
                "transitions", transitions,
                "failures", failures,
                "score", score
            ));
        }

        return rows.stream()
            .sorted((left, right) -> Integer.compare((Integer) right.get("score"), (Integer) left.get("score")))
            .limit(top)
            .toList();
    }

    private List<Map<String, Object>> buildDiagnosticFunnel(EnvironmentType environment) {
        Optional<HealthCheckRunEntity> latestRun = runRepository.findTopByEnvironmentOrderByStartedAtDesc(environment.name());
        if (latestRun.isEmpty()) {
            return List.of();
        }

        List<HealthCheckResultEntity> results = resultRepository.findByRunId(latestRun.get().getId());
        Map<String, String> diagnosticByService = new HashMap<>();
        for (HealthCheckResultEntity item : results) {
            diagnosticByService.putIfAbsent(item.getServiceKey(), Optional.ofNullable(item.getDiagnosticCode()).orElse("UNKNOWN"));
        }

        Map<String, Long> counts = new HashMap<>();
        for (String diagnostic : diagnosticByService.values()) {
            counts.put(diagnostic, counts.getOrDefault(diagnostic, 0L) + 1L);
        }

        return counts.entrySet().stream()
            .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
            .map(entry -> Map.<String, Object>of(
                "diagnostic", entry.getKey(),
                "count", entry.getValue()
            ))
            .toList();
    }

    private long toBucketEpoch(OffsetDateTime timestamp, int bucketSeconds) {
        long epoch = timestamp.toEpochSecond();
        return (epoch / bucketSeconds) * bucketSeconds;
    }

    private List<Map<String, Object>> buildSloRows(EnvironmentType environment) {
        int warnMs = properties.getChecks().getSlo().getP95LatencyWarnMs();
        int criticalMs = properties.getChecks().getSlo().getP95LatencyCriticalMs();

        List<Map<String, Object>> services = listServices(environment, null, null, null);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map<String, Object> service : services) {
            String serviceKey = String.valueOf(service.get("serviceKey"));
            String serviceName = String.valueOf(service.get("serviceName"));

            Map<String, Object> directLatency = asMap(service.get("directLatency"));
            Map<String, Object> gatewayLatency = asMap(service.get("gatewayLatency"));

            long p95Direct = longValue(directLatency.get("p95"));
            long p95Gateway = longValue(gatewayLatency.get("p95"));
            long p95Max = Math.max(p95Direct, p95Gateway);
            boolean anomaly = Boolean.TRUE.equals(directLatency.get("anomaly")) || Boolean.TRUE.equals(gatewayLatency.get("anomaly"));

            String status;
            if (p95Max >= criticalMs || anomaly) {
                status = "CRITICO";
            } else if (p95Max >= warnMs) {
                status = "ALERTA";
            } else {
                status = "OK";
            }

            rows.add(Map.of(
                "serviceKey", serviceKey,
                "serviceName", serviceName,
                "status", status,
                "p95Direct", p95Direct,
                "p95Gateway", p95Gateway,
                "anomaly", anomaly
            ));
        }

        rows.sort((left, right) -> {
            int leftWeight = sloStatusWeight(String.valueOf(left.get("status")));
            int rightWeight = sloStatusWeight(String.valueOf(right.get("status")));
            if (leftWeight != rightWeight) {
                return Integer.compare(leftWeight, rightWeight);
            }
            return String.valueOf(left.get("serviceName")).compareTo(String.valueOf(right.get("serviceName")));
        });

        return rows;
    }

    private int sloStatusWeight(String status) {
        if ("CRITICO".equals(status)) {
            return 0;
        }
        if ("ALERTA".equals(status)) {
            return 1;
        }
        return 2;
    }

    private Map<String, Object> paginateList(List<Map<String, Object>> source,
                                             int page,
                                             int size,
                                             Map<String, Object> extra) {
        int boundedSize = Math.max(1, Math.min(size, 200));
        int total = source.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) boundedSize));
        int boundedPage = Math.max(1, Math.min(page, totalPages));

        int start = (boundedPage - 1) * boundedSize;
        int end = Math.min(start + boundedSize, total);
        List<Map<String, Object>> items = start >= total ? List.of() : source.subList(start, end);

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("page", boundedPage);
        response.put("size", boundedSize);
        response.put("total", total);
        response.put("totalPages", totalPages);
        response.putAll(extra);
        return response;
    }

    private boolean isGatewayRequiredForEnvironment(EnvironmentType environment, String serviceKey) {
        return resolveServices(environment).stream()
            .filter(service -> service.key().equals(serviceKey))
            .findFirst()
            .map(service -> {
                List<String> requiredEnvs = Optional.ofNullable(service.requiredExternalEnvironments()).orElse(List.of());
                if (!requiredEnvs.isEmpty()) {
                    return requiredEnvs.stream().anyMatch(item -> environment.name().equalsIgnoreCase(item));
                }
                return environment == EnvironmentType.PRD;
            })
            .orElse(false);
    }

    private boolean hasConsecutiveFailures(EnvironmentType environment, String serviceKey, int threshold) {
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusHours(8);
        List<HealthCheckResultEntity> history = resultRepository
            .findByServiceKeyAndRunEnvironmentAndRouteTypeAndCheckedAtAfterOrderByCheckedAtDesc(
                serviceKey,
                environment.name(),
                RouteType.DIRECT.name(),
                from,
                PageRequest.of(0, threshold + 8)
            );

        int failures = 0;
        for (HealthCheckResultEntity item : history) {
            HealthState state = HealthState.valueOf(item.getHealthState());
            if (state == HealthState.UP) {
                break;
            }
            if (state == HealthState.DOWN || state == HealthState.DEGRADED || state == HealthState.UNKNOWN) {
                failures++;
            }
            if (failures >= threshold) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> alertItem(String severity,
                                          String code,
                                          String serviceKey,
                                          String serviceName,
                                          String message,
                                          OffsetDateTime detectedAt) {
        return Map.of(
            "severity", severity,
            "code", code,
            "serviceKey", serviceKey,
            "serviceName", serviceName,
            "message", message,
            "detectedAt", detectedAt
        );
    }

    private Map<String, Object> rollupForItems(List<HealthCheckResultEntity> items) {
        if (items.isEmpty()) {
            return Map.of(
                "samples", 0,
                "upRate", 0,
                "avg", 0,
                "p95", 0,
                "p99", 0
            );
        }

        List<Long> latencies = items.stream()
            .map(item -> item.getLatencyMs())
            .filter(item -> item != null && item > 0)
            .sorted()
            .toList();

        long upCount = items.stream().filter(item -> HealthState.UP.name().equals(item.getHealthState())).count();
        long upRate = Math.round((upCount * 100.0) / items.size());
        long avg = latencies.isEmpty() ? 0 : Math.round(latencies.stream().mapToLong(item -> item.longValue()).average().orElse(0));
        long p95 = latencies.isEmpty() ? 0 : percentile(latencies, 0.95);
        long p99 = latencies.isEmpty() ? 0 : percentile(latencies, 0.99);

        return Map.of(
            "samples", items.size(),
            "upRate", upRate,
            "avg", avg,
            "p95", p95,
            "p99", p99
        );
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        int boundedIndex = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(boundedIndex);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void persistResult(HealthCheckRunEntity run,
                               ServiceDefinition service,
                               RouteCheckResult result,
                               DiagnosticCode diagnosticCode) {
        HealthCheckResultEntity entity = new HealthCheckResultEntity();
        entity.setId(UUID.randomUUID());
        entity.setRun(run);
        entity.setServiceKey(service.key());
        entity.setServiceName(service.name());
        entity.setCategory(service.category());
        entity.setRouteType(result.routeType().name());
        entity.setHealthState(result.state().name());
        entity.setReasonCode(result.reasonCode());
        entity.setDiagnosticCode(diagnosticCode.name());
        entity.setHttpStatus(result.httpStatus());
        entity.setLatencyMs(result.latencyMs());
        entity.setCheckedAt(result.checkedAt());
        entity.setErrorSummary(result.errorSummary());
        resultRepository.save(entity);
    }

    private String stringifyKeys(Object keys) {
        List<String> values = new ArrayList<>();
        if (keys instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
        }

        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private List<String> parseKeys(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String asNullable(Object value) {
        if (value == null) {
            return null;
        }
        String asString = String.valueOf(value).trim();
        return asString.isEmpty() ? null : asString;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\r\n]+", " ").trim();
        if (cleaned.length() <= 280) {
            return cleaned;
        }
        return cleaned.substring(0, 280);
    }

    private List<String> monitoredCandidates(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return List.of();
        }

        String normalized = baseUrl.replaceAll("/+$", "");
        return HEALTH_PROBE_PATHS.stream().map(path -> normalized + path).toList();
    }

    private void purgeOldData() {
        int retention = Math.max(1, properties.getChecks().getHistoryRetentionDays());
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusDays(retention);
        resultRepository.deleteByCheckedAtBefore(threshold);
        runRepository.deleteByFinishedAtBefore(threshold);
    }

    private record ServiceCheckBundle(
        ServiceDefinition service,
        RouteCheckResult gateway,
        RouteCheckResult direct,
        DiagnosticCode diagnostic
    ) {
    }
}

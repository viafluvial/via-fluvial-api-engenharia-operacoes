package br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "health_check_result")
public class HealthCheckResultEntity {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id")
    private HealthCheckRunEntity run;

    @Column(name = "service_key", nullable = false, length = 80)
    private String serviceKey;

    @Column(name = "service_name", nullable = false, length = 160)
    private String serviceName;

    @Column(name = "category", nullable = false, length = 120)
    private String category;

    @Column(name = "route_type", nullable = false, length = 20)
    private String routeType;

    @Column(name = "health_state", nullable = false, length = 24)
    private String healthState;

    @Column(name = "reason_code", nullable = false, length = 48)
    private String reasonCode;

    @Column(name = "diagnostic_code", length = 64)
    private String diagnosticCode;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    @Column(name = "error_summary", length = 300)
    private String errorSummary;


    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public HealthCheckRunEntity getRun() { return run; }
    public void setRun(HealthCheckRunEntity run) { this.run = run; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getRouteType() { return routeType; }
    public void setRouteType(String routeType) { this.routeType = routeType; }
    public String getHealthState() { return healthState; }
    public void setHealthState(String healthState) { this.healthState = healthState; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getDiagnosticCode() { return diagnosticCode; }
    public void setDiagnosticCode(String diagnosticCode) { this.diagnosticCode = diagnosticCode; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public OffsetDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(OffsetDateTime checkedAt) { this.checkedAt = checkedAt; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
}

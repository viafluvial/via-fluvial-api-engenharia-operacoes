package br.com.viafluvial.engenhariaoperacoes.adapters.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "frontend_runtime_snapshot")
public class FrontendRuntimeSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "environment", nullable = false, length = 16)
    private String environment;

    @Column(name = "application_name", nullable = false, length = 120)
    private String applicationName;

    @Column(name = "transport_mode", nullable = false, length = 20)
    private String transportMode;

    @Column(name = "runtime_config_present", nullable = false)
    private boolean runtimeConfigPresent;

    @Column(name = "build_version", length = 60)
    private String buildVersion;

    @Column(name = "build_commit", length = 60)
    private String buildCommit;

    @Column(name = "enabled_service_keys", nullable = false, length = 4000)
    private String enabledServiceKeys;

    @Column(name = "client_timestamp")
    private OffsetDateTime clientTimestamp;

    @Column(name = "server_timestamp", nullable = false)
    private OffsetDateTime serverTimestamp;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
    public String getTransportMode() { return transportMode; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }
    public boolean isRuntimeConfigPresent() { return runtimeConfigPresent; }
    public void setRuntimeConfigPresent(boolean runtimeConfigPresent) { this.runtimeConfigPresent = runtimeConfigPresent; }
    public String getBuildVersion() { return buildVersion; }
    public void setBuildVersion(String buildVersion) { this.buildVersion = buildVersion; }
    public String getBuildCommit() { return buildCommit; }
    public void setBuildCommit(String buildCommit) { this.buildCommit = buildCommit; }
    public String getEnabledServiceKeys() { return enabledServiceKeys; }
    public void setEnabledServiceKeys(String enabledServiceKeys) { this.enabledServiceKeys = enabledServiceKeys; }
    public OffsetDateTime getClientTimestamp() { return clientTimestamp; }
    public void setClientTimestamp(OffsetDateTime clientTimestamp) { this.clientTimestamp = clientTimestamp; }
    public OffsetDateTime getServerTimestamp() { return serverTimestamp; }
    public void setServerTimestamp(OffsetDateTime serverTimestamp) { this.serverTimestamp = serverTimestamp; }
}

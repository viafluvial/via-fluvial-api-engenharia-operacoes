package br.com.viafluvial.engenhariaoperacoes.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "engineering")
public class EngineeringProperties {

    private Checks checks = new Checks();
    private Registry registry = new Registry();

    public Checks getChecks() {
        return checks;
    }

    public void setChecks(Checks checks) {
        this.checks = checks;
    }

    public Registry getRegistry() {
        return registry;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public static class Checks {
        private boolean enabled = true;
        private int intervalSeconds = 30;
        private int timeoutMillis = 4000;
        private int historyRetentionDays = 15;
        private int maxConcurrency = 8;
        private int retryAttempts = 2;
        private int retryBackoffMillis = 250;
        private Slo slo = new Slo();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public int getHistoryRetentionDays() {
            return historyRetentionDays;
        }

        public void setHistoryRetentionDays(int historyRetentionDays) {
            this.historyRetentionDays = historyRetentionDays;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public int getRetryBackoffMillis() {
            return retryBackoffMillis;
        }

        public void setRetryBackoffMillis(int retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
        }

        public Slo getSlo() {
            return slo;
        }

        public void setSlo(Slo slo) {
            this.slo = slo;
        }
    }

    public static class Slo {
        private int p95LatencyWarnMs = 1200;
        private int p95LatencyCriticalMs = 2500;
        private int consecutiveFailuresThreshold = 3;
        private int anomalyRatioPercent = 200;
        private int gatewayCoverageTargetPercent = 80;

        public int getP95LatencyWarnMs() {
            return p95LatencyWarnMs;
        }

        public void setP95LatencyWarnMs(int p95LatencyWarnMs) {
            this.p95LatencyWarnMs = p95LatencyWarnMs;
        }

        public int getP95LatencyCriticalMs() {
            return p95LatencyCriticalMs;
        }

        public void setP95LatencyCriticalMs(int p95LatencyCriticalMs) {
            this.p95LatencyCriticalMs = p95LatencyCriticalMs;
        }

        public int getConsecutiveFailuresThreshold() {
            return consecutiveFailuresThreshold;
        }

        public void setConsecutiveFailuresThreshold(int consecutiveFailuresThreshold) {
            this.consecutiveFailuresThreshold = consecutiveFailuresThreshold;
        }

        public int getAnomalyRatioPercent() {
            return anomalyRatioPercent;
        }

        public void setAnomalyRatioPercent(int anomalyRatioPercent) {
            this.anomalyRatioPercent = anomalyRatioPercent;
        }

        public int getGatewayCoverageTargetPercent() {
            return gatewayCoverageTargetPercent;
        }

        public void setGatewayCoverageTargetPercent(int gatewayCoverageTargetPercent) {
            this.gatewayCoverageTargetPercent = gatewayCoverageTargetPercent;
        }
    }

    public static class Registry {
        private List<ServiceEntry> services = new ArrayList<>();

        public List<ServiceEntry> getServices() {
            return services;
        }

        public void setServices(List<ServiceEntry> services) {
            this.services = services;
        }
    }

    public static class ServiceEntry {
        private String key;
        private String name;
        private String category;
        private boolean enabled = true;
        private String localDirectBaseUrl;
        private String localGatewayBaseUrl;
        private List<String> dependencies = new ArrayList<>();
        private List<String> requiredExternalEnvironments = new ArrayList<>();

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLocalDirectBaseUrl() {
            return localDirectBaseUrl;
        }

        public void setLocalDirectBaseUrl(String localDirectBaseUrl) {
            this.localDirectBaseUrl = localDirectBaseUrl;
        }

        public String getLocalGatewayBaseUrl() {
            return localGatewayBaseUrl;
        }

        public void setLocalGatewayBaseUrl(String localGatewayBaseUrl) {
            this.localGatewayBaseUrl = localGatewayBaseUrl;
        }

        public List<String> getDependencies() {
            return dependencies;
        }

        public void setDependencies(List<String> dependencies) {
            this.dependencies = dependencies;
        }

        public List<String> getRequiredExternalEnvironments() {
            return requiredExternalEnvironments;
        }

        public void setRequiredExternalEnvironments(List<String> requiredExternalEnvironments) {
            this.requiredExternalEnvironments = requiredExternalEnvironments;
        }
    }
}

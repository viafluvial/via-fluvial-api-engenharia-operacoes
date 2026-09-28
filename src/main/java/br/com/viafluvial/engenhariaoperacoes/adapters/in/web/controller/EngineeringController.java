package br.com.viafluvial.engenhariaoperacoes.adapters.in.web.controller;

import br.com.viafluvial.engenhariaoperacoes.application.usecase.EngineeringOperationsService;
import br.com.viafluvial.engenhariaoperacoes.domain.model.EnvironmentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/engineering")
@Validated
public class EngineeringController {

    private final EngineeringOperationsService service;

    public EngineeringController(EngineeringOperationsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> overview(@RequestParam(defaultValue = "DSV") String environment) {
        return service.overview(EnvironmentType.parseMonitoring(environment));
    }

    @GetMapping("/services")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public List<Map<String, Object>> services(@RequestParam(defaultValue = "DSV") String environment,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String query) {
        return service.listServices(EnvironmentType.parseMonitoring(environment), status, category, query);
    }

    @GetMapping("/services/{serviceKey}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> serviceDetails(@PathVariable String serviceKey,
                                              @RequestParam(defaultValue = "DSV") String environment) {
        return service.serviceDetails(EnvironmentType.parseMonitoring(environment), serviceKey);
    }

    @GetMapping("/services/{serviceKey}/history")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public List<Map<String, Object>> serviceHistory(@PathVariable String serviceKey,
                                                    @RequestParam(defaultValue = "DSV") String environment,
                                                    @RequestParam(defaultValue = "120") @Min(1) @Max(5000) int size) {
        return service.serviceHistory(EnvironmentType.parseMonitoring(environment), serviceKey, size);
    }

    @GetMapping("/services/{serviceKey}/rollups")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public List<Map<String, Object>> serviceRollups(@PathVariable String serviceKey,
                                                    @RequestParam(defaultValue = "DSV") String environment,
                                                    @RequestParam(defaultValue = "360") @Min(15) @Max(10080) int minutes,
                                                    @RequestParam(defaultValue = "60") @Min(30) @Max(3600) int bucketSeconds) {
        return service.serviceRollups(EnvironmentType.parseMonitoring(environment), serviceKey, minutes, bucketSeconds);
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "DSV") String environment) {
        return service.alerts(EnvironmentType.parseMonitoring(environment));
    }

    @GetMapping("/alerts/paged")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> alertsPaged(@RequestParam(defaultValue = "DSV") String environment,
                                           @RequestParam(defaultValue = "1") @Min(1) int page,
                                           @RequestParam(defaultValue = "6") @Min(1) @Max(200) int size) {
        return service.alertsPaged(EnvironmentType.parseMonitoring(environment), page, size);
    }

    @GetMapping("/slo/paged")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> sloPaged(@RequestParam(defaultValue = "DSV") String environment,
                                        @RequestParam(defaultValue = "1") @Min(1) int page,
                                        @RequestParam(defaultValue = "8") @Min(1) @Max(200) int size) {
        return service.sloPaged(EnvironmentType.parseMonitoring(environment), page, size);
    }

    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> analytics(@RequestParam(defaultValue = "DSV") String environment,
                                         @RequestParam(defaultValue = "360") @Min(30) @Max(10080) int minutes,
                                         @RequestParam(defaultValue = "300") @Min(30) @Max(3600) int bucketSeconds,
                                         @RequestParam(defaultValue = "10") @Min(3) @Max(30) int top) {
        return service.analytics(EnvironmentType.parseMonitoring(environment), minutes, bucketSeconds, top);
    }

    @GetMapping("/dependencies")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public List<Map<String, Object>> dependencies(@RequestParam(defaultValue = "DSV") String environment) {
        return service.dependencies(EnvironmentType.parseMonitoring(environment));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> runDetails(@PathVariable UUID runId) {
        return service.runDetails(runId);
    }

    @PostMapping("/checks/run")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Map<String, Object> runChecks(@RequestParam(defaultValue = "DSV") String environment, Principal principal) {
        String initiatedBy = principal == null ? "unknown" : principal.getName();
        return service.runChecks(EnvironmentType.parseMonitoring(environment), "MANUAL", initiatedBy);
    }

    @GetMapping("/frontend-runtime/latest")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> latestFrontendRuntime(@RequestParam(defaultValue = "DSV") String environment) {
        return service.latestFrontendRuntime(EnvironmentType.parseMonitoring(environment));
    }

    @PostMapping("/frontend-runtime")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPORTE','AUDITOR')")
    public Map<String, Object> saveFrontendRuntime(@RequestBody Map<String, Object> payload) {
        return service.saveFrontendRuntimeSnapshot(payload);
    }
}

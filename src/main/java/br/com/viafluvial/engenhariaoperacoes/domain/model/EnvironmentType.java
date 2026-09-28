package br.com.viafluvial.engenhariaoperacoes.domain.model;

public enum EnvironmentType {
    LOCAL,
    DSV,
    HML,
    PRD;

    public static EnvironmentType parse(String value) {
        if (value == null || value.isBlank()) {
            return DSV;
        }
        return EnvironmentType.valueOf(value.trim().toUpperCase());
    }

    public static EnvironmentType parseMonitoring(String value) {
        EnvironmentType parsed = parse(value);
        if (parsed == LOCAL) {
            throw new IllegalArgumentException("ENVIRONMENT_LOCAL_NOT_SUPPORTED");
        }
        return parsed;
    }
}

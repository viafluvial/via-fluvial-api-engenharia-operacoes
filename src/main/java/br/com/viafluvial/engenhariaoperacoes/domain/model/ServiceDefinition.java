package br.com.viafluvial.engenhariaoperacoes.domain.model;

import java.util.List;

public record ServiceDefinition(
    String key,
    String name,
    String category,
    boolean enabled,
    String directBaseUrl,
    String gatewayBaseUrl,
    List<String> dependencies,
    List<String> requiredExternalEnvironments
) {}

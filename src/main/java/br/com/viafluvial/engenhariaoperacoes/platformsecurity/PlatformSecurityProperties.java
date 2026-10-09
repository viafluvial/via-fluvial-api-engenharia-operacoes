package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.security")
public record PlatformSecurityProperties(
    String mode,
    String jwkSetUri,
    String issuer,
    String audience,
    String serviceToken,
    String serviceName,
    boolean boatmanScoped) {

  public PlatformSecurityProperties {
    if (audience == null || audience.isBlank()) {
      audience = "via-fluvial-platform";
    }
    if (serviceName == null || serviceName.isBlank()) {
      serviceName = "unknown-service";
    }
  }

  public boolean enforced() {
    return PlatformSecurity.isPlatformMode(mode);
  }

  public boolean hasServiceToken() {
    return serviceToken != null && !serviceToken.isBlank();
  }

  public boolean canVerifyUserTokens() {
    return jwkSetUri != null && !jwkSetUri.isBlank() && issuer != null && !issuer.isBlank();
  }
}

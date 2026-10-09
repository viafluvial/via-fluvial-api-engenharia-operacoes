package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

/**
 * Identity proven for the current request, either by a platform access token issued by
 * api-identidade-autenticacao or by the internal service credential.
 */
public record PlatformIdentity(
    Kind kind, String userId, String activeProfile, String boatmanId, String serviceName) {

  public enum Kind {
    USER,
    SERVICE
  }

  public static PlatformIdentity user(String userId, String activeProfile, String boatmanId) {
    return new PlatformIdentity(Kind.USER, userId, activeProfile, boatmanId, null);
  }

  public static PlatformIdentity service(String serviceName) {
    return new PlatformIdentity(Kind.SERVICE, null, null, null, serviceName);
  }
}

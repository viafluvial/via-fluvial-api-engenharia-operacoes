package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the caller identity from verifiable credentials only.
 *
 * <p>Precedence: (1) the internal service credential, which keeps the identity headers asserted by
 * the calling service and fills the missing ones from a verified user token; (2) a platform access
 * token issued by api-identidade-autenticacao, whose claims replace any identity header sent by the
 * client; (3) no credential: in platform mode identity headers are discarded and the request
 * continues as anonymous, in the legacy modes the request is left untouched.
 *
 * <p>X-Boatman-Id is an identity attribute only for boatman-scoped profiles; for every other actor
 * it is a selection parameter (for example an administrator choosing a boatman) and is preserved.
 */
public class PlatformIdentityFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(PlatformIdentityFilter.class);
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
  private static final Set<String> BOATMAN_SCOPED_PROFILES =
      Set.of("GESTOR", "BARQUEIRO", "BOATMAN", "TRIPULANTE_CHECKIN", "OPERADOR_CARGA");
  static final String VERIFIED_BEARER_ATTRIBUTE = PlatformIdentityFilter.class.getName() + ".bearer";

  private final PlatformSecurityProperties properties;
  private final JwtDecoder decoder;

  public PlatformIdentityFilter(PlatformSecurityProperties properties, JwtDecoder decoder) {
    this.properties = properties;
    this.decoder = decoder;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String bearer = bearerToken(request);
    PlatformIdentity user = bearer == null ? null : verifiedUser(bearer);

    if (isTrustedService(request)) {
      String caller = request.getHeader(PlatformSecurity.HEADER_SERVICE_NAME);
      request.setAttribute(
          PlatformSecurity.IDENTITY_ATTRIBUTE,
          PlatformIdentity.service(caller == null || caller.isBlank() ? "unknown" : caller.trim()));
      Map<String, String> overrides = new HashMap<>();
      if (user != null) {
        request.setAttribute(VERIFIED_BEARER_ATTRIBUTE, bearer);
        userHeaders(user)
            .forEach(
                (name, value) -> {
                  String sent = request.getHeader(name);
                  if (value != null && (sent == null || sent.isBlank())) {
                    overrides.put(name, value);
                  }
                });
      }
      overrides.put("Authorization", null);
      filterChain.doFilter(new PlatformTrustedHeadersRequest(request, overrides, Set.of()), response);
      return;
    }

    if (user != null) {
      request.setAttribute(PlatformSecurity.IDENTITY_ATTRIBUTE, user);
      request.setAttribute(VERIFIED_BEARER_ATTRIBUTE, bearer);
      Map<String, String> overrides = new HashMap<>(userHeaders(user));
      // Legacy filters must not try to interpret the platform token.
      overrides.put("Authorization", null);
      filterChain.doFilter(new PlatformTrustedHeadersRequest(request, overrides, Set.of()), response);
      return;
    }

    if (!properties.enforced()) {
      filterChain.doFilter(request, response);
      return;
    }

    Map<String, String> anonymous = new HashMap<>();
    anonymous.put(PlatformSecurity.HEADER_ROLES, "PASSAGEIRO");
    Set<String> removed =
        Set.of(
            PlatformSecurity.lower(PlatformSecurity.HEADER_USER_ID),
            PlatformSecurity.lower(PlatformSecurity.HEADER_INTERNAL_USER_ID));
    filterChain.doFilter(new PlatformTrustedHeadersRequest(request, anonymous, removed), response);
  }

  /** Identity headers implied by a verified user token; a null value removes the header. */
  private Map<String, String> userHeaders(PlatformIdentity user) {
    Map<String, String> headers = new HashMap<>();
    headers.put(PlatformSecurity.HEADER_USER_ID, user.userId());
    headers.put(PlatformSecurity.HEADER_INTERNAL_USER_ID, user.userId());
    headers.put(PlatformSecurity.HEADER_ROLES, serviceRole(user.activeProfile()));
    if (BOATMAN_SCOPED_PROFILES.contains(user.activeProfile())) {
      headers.put(PlatformSecurity.HEADER_BOATMAN_ID, user.boatmanId());
    }
    return headers;
  }

  private PlatformIdentity verifiedUser(String token) {
    if (decoder == null) {
      return null;
    }
    try {
      Jwt jwt = decoder.decode(token);
      String tokenUse = jwt.getClaimAsString("token_use");
      if (tokenUse != null && !"access".equals(tokenUse) && !"bootstrap".equals(tokenUse)) {
        return null;
      }
      return userIdentity(jwt);
    } catch (JwtException e) {
      log.debug("platform_token_not_verified reason={}", e.getMessage());
      return null;
    }
  }

  private PlatformIdentity userIdentity(Jwt jwt) {
    String userId = jwt.getSubject();
    if (userId == null || !UUID_PATTERN.matcher(userId).matches()) {
      return null;
    }
    String activeProfile = jwt.getClaimAsString("active_profile");
    if (activeProfile == null || activeProfile.isBlank()) {
      List<String> roles = jwt.getClaimAsStringList("roles");
      activeProfile = roles == null || roles.isEmpty() ? "PASSAGEIRO" : roles.get(0);
    }
    String boatmanId = jwt.getClaimAsString("boatman_id");
    if (boatmanId != null && !UUID_PATTERN.matcher(boatmanId).matches()) {
      boatmanId = null;
    }
    return PlatformIdentity.user(userId, activeProfile.trim().toUpperCase(Locale.ROOT), boatmanId);
  }

  /** Same role header the web app sends to this service for the given active profile. */
  String serviceRole(String activeProfile) {
    if (properties.boatmanScoped()
        && ("GESTOR".equals(activeProfile) || "BOATMAN".equals(activeProfile))) {
      return "BARQUEIRO";
    }
    return activeProfile;
  }

  private boolean isTrustedService(HttpServletRequest request) {
    if (!properties.hasServiceToken()) {
      return false;
    }
    String presented = request.getHeader(PlatformSecurity.HEADER_SERVICE_TOKEN);
    if (presented == null || presented.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.trim().getBytes(StandardCharsets.UTF_8),
        properties.serviceToken().trim().getBytes(StandardCharsets.UTF_8));
  }

  private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    String token = header.substring(7).trim();
    return token.isEmpty() ? null : token;
  }
}

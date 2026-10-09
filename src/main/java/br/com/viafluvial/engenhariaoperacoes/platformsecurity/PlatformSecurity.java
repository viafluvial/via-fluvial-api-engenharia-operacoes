package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.lang.NonNull;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared constants and helpers of the platform security contract. */
public final class PlatformSecurity {
  public static final String MODE_PLATFORM = "platform";
  public static final Set<String> SUPPORTED_MODES = Set.of("dev", "oauth2", "prod", MODE_PLATFORM);

  public static final String HEADER_USER_ID = "X-User-Id";
  public static final String HEADER_INTERNAL_USER_ID = "X-Internal-User-Id";
  public static final String HEADER_ROLES = "X-Roles";
  public static final String HEADER_BOATMAN_ID = "X-Boatman-Id";
  public static final String HEADER_SERVICE_TOKEN = "X-Platform-Service-Token";
  public static final String HEADER_SERVICE_NAME = "X-Platform-Service-Name";

  static final String IDENTITY_ATTRIBUTE = PlatformSecurity.class.getName() + ".identity";

  private PlatformSecurity() {}

  public static boolean isPlatformMode(String securityMode) {
    return MODE_PLATFORM.equalsIgnoreCase(securityMode == null ? "" : securityMode.trim());
  }

  public static PlatformIdentity identity(HttpServletRequest request) {
    Object value = request.getAttribute(IDENTITY_ATTRIBUTE);
    return value instanceof PlatformIdentity identity ? identity : null;
  }

  public static boolean isVerified(HttpServletRequest request) {
    return identity(request) != null;
  }

  /** Runs {@code delegate} only for requests whose identity was cryptographically verified. */
  public static OncePerRequestFilter verifiedOnly(jakarta.servlet.Filter delegate) {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          @NonNull HttpServletRequest request,
          @NonNull HttpServletResponse response,
          @NonNull FilterChain filterChain)
          throws ServletException, IOException {
        if (isVerified(request)) {
          delegate.doFilter(request, response, filterChain);
        } else {
          filterChain.doFilter(request, response);
        }
      }
    };
  }

  /**
   * The service's identity-header filter as it must be registered in {@code securityMode}: in
   * platform mode it only authenticates verified requests; legacy modes keep their behaviour.
   */
  public static jakarta.servlet.Filter headerAuthentication(
      String securityMode, jakarta.servlet.Filter headerFilter) {
    return isPlatformMode(securityMode) ? verifiedOnly(headerFilter) : headerFilter;
  }

  /** In platform mode unauthenticated requests get 401 with a Bearer challenge (RFC 6750). */
  public static void configureEntryPoint(HttpSecurity http, String securityMode) throws Exception {
    if (isPlatformMode(securityMode)) {
      http.exceptionHandling(
          exceptions -> exceptions.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint()));
    }
  }

  static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}

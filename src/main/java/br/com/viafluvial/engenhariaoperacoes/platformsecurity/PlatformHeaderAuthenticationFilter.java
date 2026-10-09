package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests verified by {@link PlatformIdentityFilter} from their trusted identity
 * headers, for services that have no identity-header filter of their own. Principal is the user
 * id (or the calling service name); authorities are {@code ROLE_<profile>} for each X-Roles entry.
 */
public class PlatformHeaderAuthenticationFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    PlatformIdentity identity = PlatformSecurity.identity(request);
    if (identity == null || SecurityContextHolder.getContext().getAuthentication() != null) {
      filterChain.doFilter(request, response);
      return;
    }

    String userId = request.getHeader(PlatformSecurity.HEADER_USER_ID);
    String principal =
        userId != null && !userId.isBlank()
            ? userId.trim()
            : identity.kind() == PlatformIdentity.Kind.SERVICE ? identity.serviceName() : null;
    if (principal == null) {
      filterChain.doFilter(request, response);
      return;
    }

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, authorities(request.getHeader(PlatformSecurity.HEADER_ROLES), identity)));
    SecurityContextHolder.setContext(context);
    try {
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  static List<GrantedAuthority> authorities(String rolesHeader, PlatformIdentity identity) {
    List<GrantedAuthority> authorities =
        Arrays.stream(rolesHeader == null ? new String[0] : rolesHeader.split("[,\\s]+"))
            .map(role -> role.trim().toUpperCase(Locale.ROOT))
            .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
            .filter(role -> !role.isBlank())
            .distinct()
            .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
    if (!authorities.isEmpty()) {
      return authorities;
    }
    return List.of(
        new SimpleGrantedAuthority(
            identity.kind() == PlatformIdentity.Kind.SERVICE ? "ROLE_SERVICE" : "ROLE_PASSAGEIRO"));
  }
}

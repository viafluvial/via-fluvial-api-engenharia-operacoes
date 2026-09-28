package br.com.viafluvial.engenhariaoperacoes.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class DevTokenAuthenticationFilter extends OncePerRequestFilter {

    private final Set<String> defaultRoles;

    public DevTokenAuthenticationFilter(Set<String> defaultRoles) {
        this.defaultRoles = defaultRoles;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        Jwt jwt = Jwt.withTokenValue("dev")
            .subject(resolveUser(request))
            .header("alg", "none")
            .build();

        Set<String> roles = resolveRoles(request);
        var authorities = roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveUser(HttpServletRequest request) {
        String user = request.getHeader("X-User-Id");
        return user == null || user.isBlank() ? "dev-user" : user;
    }

    private Set<String> resolveRoles(HttpServletRequest request) {
        String raw = request.getHeader("X-Roles");
        if (raw == null || raw.isBlank()) {
            return defaultRoles;
        }

        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(this::normalizeRole)
            .collect(Collectors.toSet());
    }

    private String normalizeRole(String role) {
        String normalized = role.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            return normalized;
        }
        return "ROLE_" + normalized;
    }
}

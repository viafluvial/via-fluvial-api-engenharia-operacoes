package br.com.viafluvial.engenhariaoperacoes.config;

import br.com.viafluvial.engenhariaoperacoes.common.id.CorrelationIdFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static List<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
            .map(item -> item == null ? "" : item.trim())
            .filter(item -> !item.isBlank())
            .toList();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080,http://localhost:5173,http://127.0.0.1:5173}") String allowedOrigins,
        @Value("${app.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD}") String allowedMethods,
        @Value("${app.cors.allowed-headers:*}") String allowedHeaders) {
        CorsConfiguration config = new CorsConfiguration();

        Set<String> originSet = new LinkedHashSet<>();
        originSet.addAll(splitCsv(allowedOrigins));
        if (originSet.isEmpty()) {
            originSet.add("http://localhost:5173");
        }

        List<String> headers = new ArrayList<>(splitCsv(allowedHeaders));
        if (headers.isEmpty()) {
            headers = List.of("*");
        }

        config.setAllowedOrigins(new ArrayList<>(originSet));
        config.setAllowedMethods(splitCsv(allowedMethods));
        config.setAllowedHeaders(headers);
        config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CorsConfigurationSource corsConfigurationSource,
                                            SecurityProperties securityProperties) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/openapi/**",
                    "/api/v1/actuator/health",
                    "/api/v1/actuator/info",
                    "/api/v1/swagger-ui/**",
                    "/api/v1/v3/api-docs/**",
                    "/api/v1/openapi/**")
                .permitAll()
                .anyRequest().authenticated());

        if ("oauth2".equalsIgnoreCase(securityProperties.getMode())) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        } else {
            Set<String> roles = securityProperties.getDev().getRoles().stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .collect(Collectors.toSet());
            http.addFilterBefore(new DevTokenAuthenticationFilter(roles), BasicAuthenticationFilter.class);
        }

        return http.build();
    }
}

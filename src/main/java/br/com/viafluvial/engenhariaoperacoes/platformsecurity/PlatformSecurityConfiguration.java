package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformSecurityConfiguration {

  @Bean
  FilterRegistrationBean<PlatformIdentityFilter> platformIdentityFilter(
      PlatformSecurityProperties properties) {
    validate(properties);
    JwtDecoder decoder = properties.canVerifyUserTokens() ? platformJwtDecoder(properties) : null;
    FilterRegistrationBean<PlatformIdentityFilter> registration =
        new FilterRegistrationBean<>(new PlatformIdentityFilter(properties, decoder));
    // Must run before Spring Security (-100) so every security filter sees trusted headers only.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
    return registration;
  }

  @Bean
  PlatformOutboundAuthInterceptor platformOutboundAuthInterceptor(
      PlatformSecurityProperties properties) {
    return new PlatformOutboundAuthInterceptor(properties);
  }

  @Bean
  RestClientCustomizer platformOutboundAuthRestClientCustomizer(
      PlatformOutboundAuthInterceptor interceptor) {
    return builder -> builder.requestInterceptor(interceptor);
  }

  static void validate(PlatformSecurityProperties properties) {
    String mode = properties.mode() == null ? "" : properties.mode().trim().toLowerCase(Locale.ROOT);
    if (!PlatformSecurity.SUPPORTED_MODES.contains(mode)) {
      throw new IllegalStateException(
          "SECURITY_MODE invalido: '" + properties.mode() + "'. Use platform, oauth2 ou dev.");
    }
    if (properties.enforced()) {
      if (!properties.canVerifyUserTokens()) {
        throw new IllegalStateException(
            "SECURITY_MODE=platform exige PLATFORM_JWT_JWK_SET_URI e PLATFORM_JWT_ISSUER");
      }
      if (!properties.hasServiceToken()) {
        throw new IllegalStateException("SECURITY_MODE=platform exige PLATFORM_SERVICE_TOKEN");
      }
    }
  }

  static JwtDecoder platformJwtDecoder(PlatformSecurityProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().trim())
            .restOperations(new RestTemplate(requestFactory))
            .build();
    OAuth2TokenValidator<Jwt> audience =
        jwt ->
            jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Audience invalida", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuer().trim()), audience));
    return decoder;
  }
}

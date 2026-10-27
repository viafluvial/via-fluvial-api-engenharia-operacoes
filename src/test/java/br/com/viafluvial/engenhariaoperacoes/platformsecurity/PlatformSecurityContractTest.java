package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Contract of the platform identity module; identical in every Via Fluvial API. */
class PlatformSecurityContractTest {
  private static final String ISSUER = "https://identity.test/api/v1";
  private static final String AUDIENCE = "via-fluvial-platform";
  private static final String SERVICE_TOKEN = "service-secret-for-tests";
  private static final String USER_ID = "8c4b5a2e-1f3d-4e6a-9b7c-0d1e2f3a4b5c";
  private static final String OTHER_USER_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
  private static final String BOATMAN_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
  private static final String OTHER_BOATMAN_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  private static RSAKey signingKey;
  private static RSAKey foreignKey;

  /** What the service's own security filters and controllers observe after the module ran. */
  private record Outcome(HttpServletRequest request, boolean delegateInvoked) {
    String header(String name) {
      return request.getHeader(name);
    }

    PlatformIdentity identity() {
      return PlatformSecurity.identity(request);
    }
  }

  @BeforeAll
  static void keys() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    foreignKey = new RSAKeyGenerator(2048).keyID("k1").generate();
  }

  @BeforeEach
  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  void verifiedTokenReplacesForgedIdentityHeaders() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "GESTOR", BOATMAN_ID)));
    request.addHeader("X-Roles", "ADMINISTRADOR");
    request.addHeader("X-User-Id", OTHER_USER_ID);
    request.addHeader("X-Boatman-Id", OTHER_BOATMAN_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.delegateInvoked()).isTrue();
    assertThat(outcome.identity().kind()).isEqualTo(PlatformIdentity.Kind.USER);
    assertThat(outcome.header("X-User-Id")).isEqualTo(USER_ID);
    assertThat(outcome.header("X-Internal-User-Id")).isEqualTo(USER_ID);
    assertThat(outcome.header("X-Roles")).isEqualTo("BARQUEIRO");
    assertThat(outcome.header("X-Boatman-Id")).isEqualTo(BOATMAN_ID);
    assertThat(outcome.header("Authorization")).isNull();
  }

  @Test
  void serviceWithoutBoatmanScopeKeepsTheProfileName() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "GESTOR", BOATMAN_ID)));

    Outcome outcome = run("platform", false, request);

    assertThat(outcome.header("X-Roles")).isEqualTo("GESTOR");
  }

  @Test
  void boatmanProfileWithoutScopeLosesForgedBoatmanHeader() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "GESTOR", null)));
    request.addHeader("X-Boatman-Id", OTHER_BOATMAN_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.header("X-Boatman-Id")).isNull();
  }

  @Test
  void cargoOperatorProfileCarriesBoatmanScopeFromToken() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader(
        "Authorization", bearer(token(signingKey, AUDIENCE, "OPERADOR_CARGA", BOATMAN_ID)));
    request.addHeader("X-Boatman-Id", OTHER_BOATMAN_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.header("X-Roles")).isEqualTo("OPERADOR_CARGA");
    assertThat(outcome.header("X-Boatman-Id")).isEqualTo(BOATMAN_ID);
  }

  @Test
  void cargoOperatorProfileWithoutScopeLosesForgedBoatmanHeader() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "OPERADOR_CARGA", null)));
    request.addHeader("X-Boatman-Id", OTHER_BOATMAN_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.header("X-Boatman-Id")).isNull();
  }

  @Test
  void nonScopedProfileKeepsBoatmanSelectionParameter() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "ADMINISTRADOR", null)));
    request.addHeader("X-Boatman-Id", OTHER_BOATMAN_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.header("X-Roles")).isEqualTo("ADMINISTRADOR");
    assertThat(outcome.header("X-Boatman-Id")).isEqualTo(OTHER_BOATMAN_ID);
  }

  @Test
  void anonymousRequestCannotForgeIdentity() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("X-Roles", "ADMINISTRADOR");
    request.addHeader("X-User-Id", USER_ID);
    request.addHeader("X-Internal-User-Id", USER_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.delegateInvoked()).isFalse();
    assertThat(outcome.identity()).isNull();
    assertThat(outcome.header("X-User-Id")).isNull();
    assertThat(outcome.header("X-Internal-User-Id")).isNull();
    assertThat(outcome.header("X-Roles")).isEqualTo("PASSAGEIRO");
    assertThat(java.util.Collections.list(outcome.request().getHeaderNames()))
        .noneMatch(name -> name.equalsIgnoreCase("X-User-Id"));
  }

  @Test
  void tokenSignedByAnotherKeyIsAnonymous() throws Exception {
    assertAnonymous(token(foreignKey, AUDIENCE, "ADMINISTRADOR", null));
  }

  @Test
  void tokenForAnotherAudienceIsAnonymous() throws Exception {
    assertAnonymous(token(signingKey, "other-audience", "ADMINISTRADOR", null));
  }

  @Test
  void expiredTokenIsAnonymous() throws Exception {
    assertAnonymous(
        sign(signingKey, claims(AUDIENCE, "ADMINISTRADOR").expirationTime(new Date(System.currentTimeMillis() - 120_000))));
  }

  @Test
  void tokenWithUnknownUseIsAnonymous() throws Exception {
    assertAnonymous(sign(signingKey, claims(AUDIENCE, "ADMINISTRADOR").claim("token_use", "refresh")));
  }

  @Test
  void tokenWithoutUuidSubjectIsAnonymous() throws Exception {
    assertAnonymous(sign(signingKey, claims(AUDIENCE, "ADMINISTRADOR").subject("admin")));
  }

  @Test
  void serviceCredentialKeepsAssertedHeadersAndFillsMissingOnesFromUserToken() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader(PlatformSecurity.HEADER_SERVICE_TOKEN, SERVICE_TOKEN);
    request.addHeader(PlatformSecurity.HEADER_SERVICE_NAME, "api-suporte");
    request.addHeader("X-Roles", "ROLE_SERVICE");
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "PASSAGEIRO", null)));

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.delegateInvoked()).isTrue();
    assertThat(outcome.identity().kind()).isEqualTo(PlatformIdentity.Kind.SERVICE);
    assertThat(outcome.identity().serviceName()).isEqualTo("api-suporte");
    assertThat(outcome.header("X-Roles")).isEqualTo("ROLE_SERVICE");
    assertThat(outcome.header("X-User-Id")).isEqualTo(USER_ID);
    assertThat(outcome.header("Authorization")).isNull();
  }

  @Test
  void serviceCredentialHidesUnverifiedForwardedBearer() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader(PlatformSecurity.HEADER_SERVICE_TOKEN, SERVICE_TOKEN);
    request.addHeader("Authorization", "Bearer keycloak-or-garbage");
    request.addHeader("X-User-Id", OTHER_USER_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.delegateInvoked()).isTrue();
    assertThat(outcome.header("X-User-Id")).isEqualTo(OTHER_USER_ID);
    assertThat(outcome.header("Authorization")).isNull();
  }

  @Test
  void wrongServiceCredentialIsAnonymous() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader(PlatformSecurity.HEADER_SERVICE_TOKEN, "guess");
    request.addHeader("X-Roles", "ADMINISTRADOR");

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.delegateInvoked()).isFalse();
    assertThat(outcome.header("X-Roles")).isEqualTo("PASSAGEIRO");
  }

  @Test
  void legacyModeLeavesRequestsWithoutCredentialsUntouched() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("X-Roles", "ADMINISTRADOR");
    request.addHeader("X-User-Id", OTHER_USER_ID);
    request.addHeader("Authorization", "Bearer legacy-token");

    Outcome outcome = run("prod", true, request);

    assertThat(outcome.identity()).isNull();
    assertThat(outcome.header("X-Roles")).isEqualTo("ADMINISTRADOR");
    assertThat(outcome.header("X-User-Id")).isEqualTo(OTHER_USER_ID);
    assertThat(outcome.header("Authorization")).isEqualTo("Bearer legacy-token");
  }

  @Test
  void legacyModeStillHonoursVerifiedTokens() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token(signingKey, AUDIENCE, "PASSAGEIRO", null)));
    request.addHeader("X-Roles", "ADMINISTRADOR");

    Outcome outcome = run("oauth2", true, request);

    assertThat(outcome.delegateInvoked()).isTrue();
    assertThat(outcome.header("X-Roles")).isEqualTo("PASSAGEIRO");
    assertThat(outcome.header("X-User-Id")).isEqualTo(USER_ID);
  }

  @Test
  void genericHeaderAuthenticationUsesTrustedHeadersOfVerifiedRequestsOnly() throws Exception {
    PlatformHeaderAuthenticationFilter filter = new PlatformHeaderAuthenticationFilter();
    AtomicReference<org.springframework.security.core.Authentication> seen = new AtomicReference<>();
    jakarta.servlet.FilterChain capture =
        (req, res) -> seen.set(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());

    MockHttpServletRequest user = request();
    user.addHeader("X-User-Id", USER_ID);
    user.addHeader("X-Roles", "role_gestor");
    user.setAttribute(PlatformSecurity.IDENTITY_ATTRIBUTE, PlatformIdentity.user(USER_ID, "GESTOR", BOATMAN_ID));
    filter.doFilter(user, new MockHttpServletResponse(), capture);
    assertThat(seen.get().getName()).isEqualTo(USER_ID);
    assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_GESTOR");

    MockHttpServletRequest service = request();
    service.setAttribute(PlatformSecurity.IDENTITY_ATTRIBUTE, PlatformIdentity.service("api-viagens"));
    filter.doFilter(service, new MockHttpServletResponse(), capture);
    assertThat(seen.get().getName()).isEqualTo("api-viagens");
    assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SERVICE");

    MockHttpServletRequest anonymous = request();
    anonymous.addHeader("X-User-Id", USER_ID);
    anonymous.addHeader("X-Roles", "ADMINISTRADOR");
    filter.doFilter(anonymous, new MockHttpServletResponse(), capture);
    assertThat(seen.get()).isNull();
  }

  @Test
  void configurationFailsFastOnUnsafeSettings() {
    assertThatThrownBy(() -> PlatformSecurityConfiguration.validate(props("prdo", true, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> PlatformSecurityConfiguration.validate(props("platform", false, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> PlatformSecurityConfiguration.validate(props("platform", true, false)))
        .isInstanceOf(IllegalStateException.class);
    PlatformSecurityConfiguration.validate(props("platform", true, true));
    PlatformSecurityConfiguration.validate(props("prod", false, false));
    PlatformSecurityConfiguration.validate(props("dev", false, false));
  }

  @Test
  void outboundCallsToPlatformApisCarryServiceCredentialAndVerifiedUserToken() throws Exception {
    String userToken = token(signingKey, AUDIENCE, "PASSAGEIRO", null);
    MockHttpServletRequest inbound = request();
    inbound.setAttribute(PlatformIdentityFilter.VERIFIED_BEARER_ATTRIBUTE, userToken);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
    PlatformOutboundAuthInterceptor interceptor =
        new PlatformOutboundAuthInterceptor(props("platform", true, true));

    MockClientHttpRequest platformCall =
        intercept(interceptor, "https://api-viagens.api.plataforma.prd.viafluvial.com.br/api/v1/trips");
    MockClientHttpRequest dockerCall = intercept(interceptor, "http://api-reservas:8080/api/v1/bookings");
    MockClientHttpRequest gatewayCall =
        intercept(interceptor, "https://api-gateway-gio.gateway.plataforma.prd.viafluvial.com.br/sms");
    MockClientHttpRequest externalCall = intercept(interceptor, "https://api.pagar.me/core/v5/orders");

    assertThat(platformCall.getHeaders().getFirst(PlatformSecurity.HEADER_SERVICE_TOKEN)).isEqualTo(SERVICE_TOKEN);
    assertThat(platformCall.getHeaders().getFirst("Authorization")).isEqualTo(bearer(userToken));
    assertThat(dockerCall.getHeaders().getFirst(PlatformSecurity.HEADER_SERVICE_TOKEN)).isEqualTo(SERVICE_TOKEN);
    assertThat(gatewayCall.getHeaders().containsKey(PlatformSecurity.HEADER_SERVICE_TOKEN)).isFalse();
    assertThat(gatewayCall.getHeaders().containsKey("Authorization")).isFalse();
    assertThat(externalCall.getHeaders().containsKey(PlatformSecurity.HEADER_SERVICE_TOKEN)).isFalse();
    assertThat(externalCall.getHeaders().containsKey("Authorization")).isFalse();
  }

  private void assertAnonymous(String token) throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", bearer(token));
    request.addHeader("X-User-Id", USER_ID);

    Outcome outcome = run("platform", true, request);

    assertThat(outcome.delegateInvoked()).isFalse();
    assertThat(outcome.identity()).isNull();
    assertThat(outcome.header("X-User-Id")).isNull();
    assertThat(outcome.header("X-Roles")).isEqualTo("PASSAGEIRO");
  }

  private static MockClientHttpRequest intercept(PlatformOutboundAuthInterceptor interceptor, String url)
      throws Exception {
    MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create(url));
    ClientHttpRequestExecution execution =
        (req, body) -> new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
    interceptor.intercept(request, new byte[0], execution);
    return request;
  }

  private Outcome run(String mode, boolean boatmanScoped, MockHttpServletRequest request) throws Exception {
    PlatformSecurityProperties properties =
        new PlatformSecurityProperties(
            mode, "https://identity.test/jwks", ISSUER, AUDIENCE, SERVICE_TOKEN, "api-test", boatmanScoped);
    PlatformIdentityFilter identityFilter = new PlatformIdentityFilter(properties, decoder());
    AtomicBoolean delegateInvoked = new AtomicBoolean();
    Filter delegate =
        (req, res, chain) -> {
          delegateInvoked.set(true);
          chain.doFilter(req, res);
        };
    Filter verifiedOnly = PlatformSecurity.verifiedOnly(delegate);
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

    identityFilter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> verifiedOnly.doFilter(req, res, (r, s) -> seen.set((HttpServletRequest) r)));

    return new Outcome(seen.get(), delegateInvoked.get());
  }

  private static PlatformSecurityProperties props(String mode, boolean jwks, boolean serviceToken) {
    return new PlatformSecurityProperties(
        mode,
        jwks ? "https://identity.test/jwks" : "",
        jwks ? ISSUER : "",
        AUDIENCE,
        serviceToken ? SERVICE_TOKEN : "",
        "api-test",
        false);
  }

  private static NimbusJwtDecoder decoder() throws Exception {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER),
            jwt ->
                jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return decoder;
  }

  private static JWTClaimsSet.Builder claims(String audience, String profile) {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(audience)
        .subject(USER_ID)
        .issueTime(new Date())
        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
        .claim("token_use", "access")
        .claim("active_profile", profile)
        .claim("roles", List.of(profile));
  }

  private static String token(RSAKey key, String audience, String profile, String boatmanId) throws Exception {
    JWTClaimsSet.Builder claims = claims(audience, profile);
    if (boatmanId != null) {
      claims.claim("boatman_id", boatmanId);
    }
    return sign(key, claims);
  }

  private static String sign(RSAKey key, JWTClaimsSet.Builder claims) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/resource");
    request.setServletPath("/api/v1");
    return request;
  }
}

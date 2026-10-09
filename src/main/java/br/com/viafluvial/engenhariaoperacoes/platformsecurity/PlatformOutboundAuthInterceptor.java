package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Authenticates outbound calls to other Via Fluvial APIs with the internal service credential and
 * forwards the verified user token when the call happens inside a user request.
 */
public class PlatformOutboundAuthInterceptor implements ClientHttpRequestInterceptor {
  private static final Pattern PLATFORM_API_HOST =
      Pattern.compile("^api-[a-z0-9-]+(\\.api\\.plataforma\\.(prd|hml|dsv)\\.viafluvial\\.com\\.br)?$");

  private final PlatformSecurityProperties properties;

  public PlatformOutboundAuthInterceptor(PlatformSecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    boolean hasAuthorization = request.getHeaders().containsKey("Authorization");
    headersFor(request.getURI(), hasAuthorization).forEach(request.getHeaders()::set);
    return execution.execute(request, body);
  }

  /**
   * Headers that authenticate a call to {@code uri}; empty for hosts outside the platform. Used
   * directly by clients that are not built with RestClient (for example java.net.http).
   */
  public Map<String, String> headersFor(URI uri, boolean hasAuthorization) {
    if (!properties.hasServiceToken() || !isPlatformApi(uri)) {
      return Map.of();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(PlatformSecurity.HEADER_SERVICE_TOKEN, properties.serviceToken().trim());
    headers.put(PlatformSecurity.HEADER_SERVICE_NAME, properties.serviceName());
    String userBearer = currentVerifiedBearer();
    if (userBearer != null && !hasAuthorization) {
      headers.put("Authorization", "Bearer " + userBearer);
    }
    return headers;
  }

  public static boolean isPlatformApi(URI uri) {
    if (uri == null || uri.getHost() == null) {
      return false;
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return PLATFORM_API_HOST.matcher(host).matches()
        || host.equals("localhost")
        || host.equals("127.0.0.1")
        || host.equals("172.17.0.1")
        || host.equals("host.docker.internal");
  }

  private static String currentVerifiedBearer() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      Object bearer = request.getAttribute(PlatformIdentityFilter.VERIFIED_BEARER_ATTRIBUTE);
      return bearer instanceof String value && !value.isBlank() ? value : null;
    }
    return null;
  }
}

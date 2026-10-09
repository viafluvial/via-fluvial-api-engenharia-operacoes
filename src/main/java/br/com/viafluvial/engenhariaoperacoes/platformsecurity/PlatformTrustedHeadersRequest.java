package br.com.viafluvial.engenhariaoperacoes.platformsecurity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request view where identity headers can only hold server-verified values. */
final class PlatformTrustedHeadersRequest extends HttpServletRequestWrapper {
  private final Map<String, String> overrides;
  private final Set<String> removed;

  PlatformTrustedHeadersRequest(
      HttpServletRequest request, Map<String, String> overrides, Set<String> removed) {
    super(request);
    this.overrides = new LinkedHashMap<>();
    overrides.forEach((name, value) -> this.overrides.put(PlatformSecurity.lower(name), value));
    this.removed = removed;
  }

  @Override
  public String getHeader(String name) {
    String key = PlatformSecurity.lower(name);
    if (overrides.containsKey(key)) {
      return overrides.get(key);
    }
    if (removed.contains(key)) {
      return null;
    }
    return super.getHeader(name);
  }

  @Override
  public Enumeration<String> getHeaders(String name) {
    String key = PlatformSecurity.lower(name);
    if (overrides.containsKey(key)) {
      String value = overrides.get(key);
      return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
    }
    if (removed.contains(key)) {
      return Collections.emptyEnumeration();
    }
    return super.getHeaders(name);
  }

  @Override
  public Enumeration<String> getHeaderNames() {
    List<String> names = new ArrayList<>();
    Enumeration<String> original = super.getHeaderNames();
    while (original != null && original.hasMoreElements()) {
      String name = original.nextElement();
      String key = PlatformSecurity.lower(name);
      if (!removed.contains(key) && !overrides.containsKey(key)) {
        names.add(name);
      }
    }
    overrides.forEach(
        (name, value) -> {
          if (value != null) {
            names.add(name);
          }
        });
    return Collections.enumeration(names);
  }
}

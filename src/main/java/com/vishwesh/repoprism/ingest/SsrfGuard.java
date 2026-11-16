package com.vishwesh.repoprism.ingest;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Validates user-supplied repository URLs before any outbound request.
 *
 * <p>Rules: only {@code https}, only {@code github.com}, no credentials, port, query or fragment,
 * exactly {@code /owner/name}, and the resolved host must not be a loopback/private/link-local/
 * site-local address. Outbound HTTP clients must not follow redirects.
 */
@Component
public class SsrfGuard {

  static final String ALLOWED_HOST = "github.com";

  private final HostResolver resolver;

  SsrfGuard(HostResolver resolver) {
    this.resolver = resolver;
  }

  public GithubUrl validate(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new InvalidRepositoryUrlException("Repository URL is required");
    }
    URI uri;
    try {
      uri = new URI(rawUrl.trim());
    } catch (URISyntaxException e) {
      throw new InvalidRepositoryUrlException("Malformed repository URL", e);
    }

    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new InvalidRepositoryUrlException("Only https://github.com URLs are allowed");
    }
    if (uri.getUserInfo() != null) {
      throw new InvalidRepositoryUrlException("Credentials are not allowed in repository URLs");
    }
    if (uri.getPort() != -1 && uri.getPort() != 443) {
      throw new InvalidRepositoryUrlException("Explicit ports are not allowed");
    }
    if (uri.getQuery() != null || uri.getFragment() != null) {
      throw new InvalidRepositoryUrlException("Query strings and fragments are not allowed");
    }
    String host = uri.getHost();
    if (host == null || !ALLOWED_HOST.equals(host.toLowerCase(Locale.ROOT))) {
      throw new InvalidRepositoryUrlException("Only github.com repositories are allowed");
    }

    List<String> segments = pathSegments(uri.getRawPath());
    if (segments.size() != 2) {
      throw new InvalidRepositoryUrlException("URL must be https://github.com/{owner}/{name}");
    }

    assertPublicHost(host);

    return new GithubUrl(segments.get(0), segments.get(1));
  }

  private static List<String> pathSegments(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return List.of();
    }
    String normalized =
        rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
    return java.util.Arrays.stream(normalized.split("/")).filter(s -> !s.isEmpty()).toList();
  }

  private void assertPublicHost(String host) {
    List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new InvalidRepositoryUrlException("Cannot resolve " + host, e);
    }
    if (addresses.isEmpty()) {
      throw new InvalidRepositoryUrlException("Cannot resolve " + host);
    }
    for (InetAddress address : addresses) {
      if (!isPublic(address)) {
        throw new InvalidRepositoryUrlException(
            "Refusing to contact non-public address for " + host);
      }
    }
  }

  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    if (address instanceof Inet4Address) {
      byte[] octets = address.getAddress();
      int first = octets[0] & 0xff;
      int second = octets[1] & 0xff;
      if (first == 0 || first == 10 || first == 127) {
        return false;
      }
      if (first == 169 && second == 254) {
        return false;
      }
      if (first == 172 && second >= 16 && second <= 31) {
        return false;
      }
      if (first == 192 && second == 168) {
        return false;
      }
      if (first == 100 && second >= 64 && second <= 127) {
        return false;
      }
      return !(first >= 224);
    }
    if (address instanceof Inet6Address) {
      byte[] bytes = address.getAddress();
      int first = bytes[0] & 0xff;
      return !((first & 0xfe) == 0xfc);
    }
    return true;
  }
}

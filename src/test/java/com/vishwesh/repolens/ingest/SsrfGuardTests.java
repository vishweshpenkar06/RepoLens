package com.vishwesh.repolens.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTests {

  private static final HostResolver PUBLIC =
      host -> List.of(InetAddress.getByAddress(new byte[] {(byte) 140, 82, 113, 4}));

  private final SsrfGuard guard = new SsrfGuard(PUBLIC);

  @Test
  void acceptsCanonicalGithubUrl() {
    assertThat(guard.validate("https://github.com/acme/demo"))
        .isEqualTo(new GithubUrl("acme", "demo"));
  }

  @Test
  void acceptsTrailingSlash() {
    assertThat(guard.validate("https://github.com/acme/demo/").slug()).isEqualTo("acme/demo");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://github.com/acme/demo",
        "ftp://github.com/acme/demo",
        "https://github.com.evil.com/acme/demo",
        "https://evil.com/acme/demo",
        "https://140.82.113.4/acme/demo",
        "https://user:pass@github.com/acme/demo",
        "https://github.com:8443/acme/demo",
        "https://github.com/acme/demo?x=1",
        "https://github.com/acme/demo#frag",
        "https://github.com/acme",
        "https://github.com/acme/demo/extra",
        "not a url",
        ""
      })
  void rejectsUnsafeUrls(String url) {
    assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(InvalidRepositoryUrlException.class);
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> guard.validate(null))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.0.0.1", "127.0.0.1", "169.254.1.1", "192.168.1.1", "172.16.0.1"})
  void rejectsPrivateIpv4Resolution(String address) throws Exception {
    InetAddress resolved = InetAddress.getByName(address);
    SsrfGuard privateGuard = new SsrfGuard(host -> List.of(resolved));
    assertThatThrownBy(() -> privateGuard.validate("https://github.com/acme/demo"))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }

  @Test
  void rejectsUniqueLocalIpv6() throws Exception {
    InetAddress resolved = InetAddress.getByName("fc00::1");
    SsrfGuard privateGuard = new SsrfGuard(host -> List.of(resolved));
    assertThatThrownBy(() -> privateGuard.validate("https://github.com/acme/demo"))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }

  @Test
  void rejectsUnresolvableHost() {
    SsrfGuard failing =
        new SsrfGuard(host -> {
          throw new UnknownHostException(host);
        });
    assertThatThrownBy(() -> failing.validate("https://github.com/acme/demo"))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }

  @Test
  void rejectsEmptyResolution() {
    SsrfGuard empty = new SsrfGuard(host -> List.of());
    assertThatThrownBy(() -> empty.validate("https://github.com/acme/demo"))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }
}

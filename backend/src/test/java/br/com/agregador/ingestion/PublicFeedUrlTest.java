package br.com.agregador.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicFeedUrlTest {
  @Test
  void registrationNormalizesWithoutDnsAndPreservesRawPathQuery() {
    assertEquals(
        "https://not-resolved.example/a%2Fb?key=A%2FB&x=1",
        PublicFeedUrl.normalize("HTTPS://NOT-RESOLVED.EXAMPLE:443/a%2Fb?key=A%2FB&x=1"));
    assertEquals("http://example.com/", PublicFeedUrl.normalize("http://example.com:80"));
    assertEquals(
        "https://[2606:4700:4700::1111]/feed",
        PublicFeedUrl.normalize("https://[2606:4700:4700::1111]/feed"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:///etc/passwd",
        "ftp://example.com/feed",
        "https://user:secret@example.com/feed",
        "http://localhost/feed",
        "http://metadata.google.internal/feed",
        "http://printer.local/feed",
        "http://127.0.0.1/feed",
        "http://127.1/feed",
        "http://2130706433/feed",
        "http://0x7f000001/feed",
        "http://0177.0.0.1/feed",
        "http://0.0.0.0/",
        "http://10.0.0.1/",
        "http://172.16.0.1/",
        "http://192.168.0.1/",
        "http://169.254.169.254/",
        "http://100.100.100.200/",
        "http://192.0.0.1/",
        "http://192.0.2.1/",
        "http://198.18.0.1/",
        "http://198.51.100.1/",
        "http://203.0.113.1/",
        "http://224.0.0.1/",
        "http://255.255.255.255/",
        "http://[::1]/",
        "http://[::]/",
        "http://[fc00::1]/",
        "http://[fe80::1]/",
        "http://[ff02::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://[::ffff:8.8.8.8]/",
        "http://[2001:db8::1]/",
        "http://[2002:7f00:1::]/",
        "http://[64:ff9b::7f00:1]/",
        "http://[3fff::1]/",
        "http://example.com:/",
        "http://example.com:0/",
        "http://example.com:65536/",
        "http://example.com/#secret",
        "http://example.com./#fragment",
        "http://[fe80::1%25eth0]/"
      })
  void rejectsUnsafeOrAmbiguousDestinationsWithoutDns(String url) {
    assertThrows(IllegalArgumentException.class, () -> PublicFeedUrl.normalize(url));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "8.8.8.8",
        "93.184.216.34",
        "100.128.0.1",
        "172.32.0.1",
        "192.0.1.1",
        "198.20.0.1",
        "2001:4860:4860::8888",
        "2606:4700:4700::1111"
      })
  void permitsGlobalAddresses(String address) throws Exception {
    assertTrue(PublicFeedUrl.isPublicAddress(InetAddress.getByName(address)));
  }
}

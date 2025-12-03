package com.ibm.cloud.sdk.core.test.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.sdk.core.har.HAREncoder;
import com.ibm.cloud.sdk.core.http.ServiceCall;
import com.ibm.cloud.sdk.core.security.NoAuthAuthenticator;
import com.ibm.cloud.sdk.core.service.BaseService;
import com.ibm.cloud.sdk.core.util.ResponseConverterUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BaseServiceHarTest {
  private MockWebServer server;
  private Path harFile;
  private final ObjectMapper mapper = new ObjectMapper();

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    HAREncoder.resetForTesting();
  }

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.shutdown();
    }
    HAREncoder.resetForTesting();
    if (harFile != null) {
      Files.deleteIfExists(harFile);
    }
  }

  @Test
  public void recordsHarFromServiceCall() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{\"status\":\"ok\"}"));

    harFile = Files.createTempFile("service-har", ".har");
    HAREncoder.enableForTesting(harFile);

    TestService svc = new TestService(server.url("/").toString());
    ServiceCall<String> call = svc.performGet();
    String body = call.execute().getResult();
    assertEquals("{\"status\":\"ok\"}", body);

    JsonNode entry = mapper.readTree(Files.readAllBytes(harFile)).path("log").path("entries").get(0);
    assertEquals("GET", entry.path("request").path("method").asText());
    assertEquals(200, entry.path("response").path("status").asInt());

    JsonNode headers = entry.path("request").path("headers");
    assertNotNull(headers);
    boolean foundAuth = false;
    for (JsonNode header : headers) {
      if ("Authorization".equalsIgnoreCase(header.path("name").asText())) {
        foundAuth = true;
        assertTrue(header.path("value").asText().contains("[REDACTED_BEARER_TOKEN]"));
      }
    }
    assertTrue(foundAuth);
  }

  private static class TestService extends BaseService {
    TestService(String url) {
      super("har-test", new NoAuthAuthenticator());
      setServiceUrl(url);
    }

    ServiceCall<String> performGet() {
      Request request = new Request.Builder()
          .url(getServiceUrl() + "/example?token=value")
          .header("Authorization", "Bearer secret-token")
          .get()
          .build();

      return createServiceCall(request, ResponseConverterUtils.getString());
    }
  }
}

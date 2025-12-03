package com.ibm.cloud.sdk.core.har;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.sdk.core.har.HAREncoder.HarEntryInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HAREncoderTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Before
  public void setUp() {
    HAREncoder.resetForTesting();
  }

  @After
  public void tearDown() {
    HAREncoder.resetForTesting();
    // Clean up any HAR files created during tests
    try {
      if (HAREncoder.getInstance().getHarPath() != null) {
        Path harPath = HAREncoder.getInstance().getHarPath();
        if (harPath != null && Files.exists(harPath)) {
          Files.deleteIfExists(harPath);
          // Also clean up any rotated files in the same directory
          if (harPath.getParent() != null) {
            Files.list(harPath.getParent())
                .filter(p -> p.getFileName().toString().endsWith(".har"))
                .forEach(p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (Exception e) {
                    // Ignore cleanup failures
                  }
                });
          }
        }
      }
    } catch (Exception e) {
      // Ignore cleanup failures
    }
  }

  @Test
  public void disabledByDefault() {
    assertFalse(HAREncoder.getInstance().isEnabled());
  }

  @Test
  public void writesHarWithRedaction() throws Exception {
    Path tmp = Files.createTempFile("har-test", ".har");
    HAREncoder.enableForTesting(tmp);

    HarEntryInput input = new HarEntryInput();
    input.setRequestUrl("https://example.com/api?token=abc");
    input.setMethod("POST");
    input.setRequestContentType("application/json");
    input.setRequestBody(
        "{\"password\":\"secret\",\"token\":\"abc1234567890tokenvaluewithlength\"}".getBytes());
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("Authorization", Arrays.asList("Bearer mytoken"));
    input.setRequestHeaders(headers);
    input.setStatusCode(200);
    input.setStatusText("200 OK");
    input.setResponseContentType("application/json");
    input.setResponseBody("{\"api_key\":\"abc\"}".getBytes());
    input.setStartTime(Instant.now());
    input.setEndTime(Instant.now().plusMillis(25));

    HAREncoder.getInstance().append(input);

    JsonNode root = mapper.readTree(Files.readAllBytes(tmp));
    JsonNode entry = root.path("log").path("entries").get(0);
    String auth = entry.path("request").path("headers").get(0).path("value").asText();
    assertTrue(auth.contains("[REDACTED_BEARER_TOKEN]"));
    String reqBody = entry.path("request").path("postData").path("text").asText();
    assertTrue(reqBody.contains("[REDACTED_PASSWORD]"));
    assertTrue(reqBody.contains("[REDACTED_TOKEN]"));
    String respBody = entry.path("response").path("content").path("text").asText();
    assertTrue(respBody.contains("[REDACTED_API_KEY]"));
  }

  @Test
  public void encodesBinaryResponses() throws Exception {
    Path tmp = Files.createTempFile("har-binary", ".har");
    HAREncoder.enableForTesting(tmp);

    byte[] binary = new byte[] {0x00, 0x01, 0x02, 0x03};
    HarEntryInput input = new HarEntryInput();
    input.setRequestUrl("https://example.com/file");
    input.setMethod("GET");
    input.setStatusCode(200);
    input.setStatusText("OK");
    input.setResponseBody(binary);
    input.setResponseContentType("application/octet-stream");
    input.setStartTime(Instant.now());
    input.setEndTime(Instant.now().plusMillis(5));

    HAREncoder.getInstance().append(input);

    JsonNode entry = mapper.readTree(Files.readAllBytes(tmp)).path("log").path("entries").get(0);
    JsonNode content = entry.path("response").path("content");
    assertEquals("base64", content.path("encoding").asText());
    assertEquals(java.util.Base64.getEncoder().encodeToString(binary), content.path("text").asText());
  }

  @Test
  public void redactsNonJsonSecrets() throws Exception {
    Path tmp = Files.createTempFile("har-text", ".har");
    HAREncoder.enableForTesting(tmp);

    HarEntryInput input = new HarEntryInput();
    input.setRequestUrl("https://example.com/file");
    input.setMethod("POST");
    input.setRequestContentType("text/plain");
    input.setRequestBody("Bearer abcdef123456".getBytes());
    input.setStatusCode(200);
    input.setStatusText("OK");
    input.setResponseBody("apikey=12345".getBytes());
    input.setResponseContentType("text/plain");
    input.setStartTime(Instant.now());
    input.setEndTime(Instant.now().plusMillis(1));

    HAREncoder.getInstance().append(input);

    JsonNode entry = mapper.readTree(Files.readAllBytes(tmp)).path("log").path("entries").get(0);
    String reqText = entry.path("request").path("postData").path("text").asText();
    assertTrue(reqText.contains("[REDACTED_BEARER_TOKEN]"));
    String respText = entry.path("response").path("content").path("text").asText();
    assertTrue(respText.contains("[REDACTED_API_KEY]"));
  }

  @Test
  public void rotatesWhenExceedingMaxEntries() throws Exception {
    Path dir = Files.createTempDirectory("har-rotate");
    Path harFile = dir.resolve("test.har");
    HAREncoder.enableForTesting(harFile);
    HAREncoder.setMaxEntriesForTesting(2);

    for (int i = 0; i < 3; i++) {
      HarEntryInput input = new HarEntryInput();
      input.setRequestUrl("https://example.com/" + i);
      input.setMethod("GET");
      input.setStatusCode(200);
      input.setStatusText("OK");
      input.setStartTime(Instant.now());
      input.setEndTime(Instant.now().plusMillis(1));
      Thread.sleep(10); // Small delay to ensure timestamps are different
      HAREncoder.getInstance().append(input);
    }

    // List all files in directory
    List<Path> files = Files.list(dir)
        .filter(p -> p.getFileName().toString().endsWith(".har"))
        .collect(Collectors.toList());

    // Should have current file + rotated file = 2 total
    assertEquals("Should have 2 HAR files after rotation",2, files.size());

    // Current file should have only the last entry
    Path currentHar = dir.resolve("test.har");
    assertTrue(Files.exists(currentHar));
    JsonNode current = mapper.readTree(Files.readAllBytes(currentHar));
    assertEquals("Current HAR should have 1 entry after rotation", 1, current.path("log").path("entries").size());
  }
}

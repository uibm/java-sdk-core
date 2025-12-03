package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * HAR encoder responsible for persisting request/response pairs to a HAR 1.2 file.
 */
public class HAREncoder {
  private static final Logger LOG = Logger.getLogger(HAREncoder.class.getName());
  private static final int DEFAULT_MAX_ENTRIES = 10_000;
  private static final double BINARY_THRESHOLD = 0.05d;
  private static final int MAX_SAMPLE = 8192;

  private static final Pattern BEARER_PATTERN =
      Pattern.compile("(?i)(bearer\\s+)([a-zA-Z0-9\\-._~+/]+=*)");
  private static final Pattern BASIC_PATTERN =
      Pattern.compile("(?i)(basic\\s+)([a-zA-Z0-9+/]+=*)");
  private static final Pattern APIKEY_PATTERN =
      Pattern.compile("(?i)(apikey[\\s:=]+)([a-zA-Z0-9\\-._~+/]+)");
  private static final Pattern TOKEN_PATTERN =
      Pattern.compile("(?i)(token[\\s:=]+)([a-zA-Z0-9\\-._~+/]+)");
  private static final Pattern IAM_TOKEN_PATTERN =
      Pattern.compile("(?i)(iam[_-]?token[\\s:=]+)([a-zA-Z0-9\\-._~+/]+)");
  private static final Pattern ACCESS_TOKEN_PATTERN =
      Pattern.compile("(?i)(access[_-]?token[\\s:=]+)([a-zA-Z0-9\\-._~+/]+)");
  private static final Pattern SESSION_TOKEN_PATTERN =
      Pattern.compile("(?i)(session[_-]?token[\\s:=]+)([a-zA-Z0-9\\-._~+/]+)");
  private static final Pattern PASSWORD_PATTERN =
      Pattern.compile("(?i)(password[\\s:=]+)([^\\s&\"'<>]+)");
  private static final Pattern SECRET_PATTERN =
      Pattern.compile("(?i)(secret[\\s:=]+)([a-zA-Z0-9\\-._~+/]+)");
  private static final Pattern COOKIE_PATTERN =
      Pattern.compile("(?i)(=[^;,\\s]{8,})(;|,|$)");
  private static final Pattern TOKEN_LIKE_PATTERN =
      Pattern.compile("^[A-Za-z0-9\\-._~+/]+=*$");

  private static final List<String> SENSITIVE_HEADER_PATTERNS =
      Arrays.asList(
          "authorization",
          "cookie",
          "set-cookie",
          "token",
          "apikey",
          "api-key",
          "secret",
          "password",
          "credential",
          "session",
          "x-auth",
          "x-api");

  private static final List<String> SENSITIVE_JSON_KEYS =
      Arrays.asList(
          "token",
          "apikey",
          "api_key",
          "password",
          "secret",
          "authorization",
          "auth",
          "credential",
          "access_token",
          "refresh_token",
          "session_token",
          "bearer",
          "api-key",
          "iam_token",
          "session_id",
          "cookie",
          "sessionid");

  private static final AtomicBoolean initialized = new AtomicBoolean(false);
  private static final AtomicBoolean enabled = new AtomicBoolean(false);
  private static Path harPath;
  private static final HAREncoder INSTANCE = new HAREncoder();
  private static int maxEntries = DEFAULT_MAX_ENTRIES;

  private final ObjectMapper mapper;
  private final ReentrantLock lock = new ReentrantLock();

  private HAREncoder() {
    mapper = new ObjectMapper();
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

  public static HAREncoder getInstance() {
    return INSTANCE;
  }

  public boolean isEnabled() {
    if (!initialized.get()) {
      synchronized (HAREncoder.class) {
        if (!initialized.get()) {
          boolean flag = "1".equals(System.getenv("HAR_ENABLED"));
          enabled.set(flag);
          if (flag) {
            String custom = System.getenv("HAR_FILE_PATH");
            if (custom != null && !custom.isEmpty()) {
              harPath = Paths.get(custom);
            } else {
              harPath = Paths.get(System.getProperty("java.io.tmpdir"), "ibm-java-sdk-core.har");
            }
            LOG.info("HAR recording enabled, writing to: " + harPath);
          }
          initialized.set(true);
        }
      }
    }
    return enabled.get();
  }

  public Path getHarPath() {
    if (!isEnabled()) {
      return null;
    }
    return harPath;
  }

  /** Testing helper to reset cached enablement state. */
  public static void resetForTesting() {
    synchronized (HAREncoder.class) {
      initialized.set(false);
      enabled.set(false);
      harPath = null;
      maxEntries = DEFAULT_MAX_ENTRIES;
    }
  }

  /** Testing helper to force-enable HAR writing to a specific path. */
  public static void enableForTesting(Path path) {
    synchronized (HAREncoder.class) {
      initialized.set(true);
      enabled.set(true);
      harPath = path;
    }
  }

  /** Testing helper to lower rotation threshold. */
  static void setMaxEntriesForTesting(int value) {
    maxEntries = value;
  }

  public void append(HarEntryInput input) {
    if (input == null || input.getRequestUrl() == null || !isEnabled()) {
      return;
    }

    try {
      HarEntry entry = buildHarEntry(input);
      lock.lock();
      try {
        HarArchive archive = readOrCreateHar();
        if (archive.getLog().getEntries().size() >= maxEntries) {
          rotateHarFile();
          archive = createNewArchive();
        }
        archive.getLog().getEntries().add(entry);
        writeHar(archive);
      } finally {
        lock.unlock();
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to append HAR entry", e);
    }
  }

  private HarEntry buildHarEntry(HarEntryInput input) {
    HarEntry entry = new HarEntry();
    entry.setPageref("page_1");
    entry.setStartedDateTime(OffsetDateTime.ofInstant(input.getStartTime(), ZoneOffset.UTC));
    long durationMs = input.getEndTime().toEpochMilli() - input.getStartTime().toEpochMilli();
    entry.setTime(durationMs);
    entry.setRequest(buildRequest(input));
    entry.setResponse(buildResponse(input));
    HarTimings timings = new HarTimings();
    timings.setSend(-1);
    timings.setReceive(-1);
    timings.setWait(durationMs);
    entry.setTimings(timings);
    return entry;
  }

  private HarRequest buildRequest(HarEntryInput input) {
    HarRequest req = new HarRequest();
    req.setMethod(input.getMethod());
    req.setUrl(input.getRequestUrl());
    req.setHttpVersion(getHttpVersion(input.getRequestProtocol()));
    req.setHeaders(convertHeaders(input.getRequestHeaders(), true));
    req.setQueryString(convertQuery(input.getQueryParams()));
    req.setHeadersSize(-1);

    if (input.getRequestBody() != null && input.getRequestBody().length > 0) {
      HarPostData post = new HarPostData();
      post.setMimeType(nullToEmpty(input.getRequestContentType()));
      BodyProcessingResult processed =
          processBody(input.getRequestBody(), true, nullToEmpty(input.getRequestContentType()));
      if (!processed.text.isEmpty() || !processed.encoding.isEmpty()) {
        post.setText(processed.text);
        req.setPostData(post);
      }
    }

    req.setBodySize(input.getRequestBody() == null ? 0 : input.getRequestBody().length);
    return req;
  }

  private HarResponse buildResponse(HarEntryInput input) {
    HarResponse resp = new HarResponse();
    resp.setStatus(getStatusCode(input));
    resp.setStatusText(getStatusText(input));
    resp.setHttpVersion(getHttpVersion(input.getResponseProtocol()));
    resp.setHeaders(convertHeaders(input.getResponseHeaders(), false));
    resp.setHeadersSize(-1);

    byte[] body = input.getResponseBody() == null ? new byte[0] : input.getResponseBody();
    BodyProcessingResult processed =
        processBody(body, false, nullToEmpty(input.getResponseContentType()));
    HarContent content = new HarContent();
    content.setMimeType(nullToEmpty(input.getResponseContentType()));
    content.setText(processed.text);
    if (!processed.encoding.isEmpty()) {
      content.setEncoding(processed.encoding);
    }
    content.setSize(body.length);
    resp.setContent(content);
    resp.setBodySize(body.length);
    if (input.getStatusCode() >= 300 && input.getStatusCode() < 400) {
      resp.setRedirectURL(getHeaderValue(input.getResponseHeaders(), "Location"));
    }
    return resp;
  }

  private List<HarNameValuePair> convertHeaders(Map<String, List<String>> headers, boolean isRequest) {
    List<HarNameValuePair> result = new ArrayList<>();
    if (headers == null) {
      return result;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String name = entry.getKey();
      List<String> values = entry.getValue();
      if (values == null) {
        continue;
      }
      for (String value : values) {
        if (isSensitiveHeader(name)) {
          value = redactSecretValue(value);
        }
        result.add(new HarNameValuePair(name, value));
      }
    }
    return result;
  }

  private List<HarNameValuePair> convertQuery(Map<String, List<String>> query) {
    List<HarNameValuePair> result = new ArrayList<>();
    if (query == null) {
      return result;
    }
    for (Map.Entry<String, List<String>> entry : query.entrySet()) {
      String name = entry.getKey();
      List<String> values = entry.getValue();
      if (values == null) {
        continue;
      }
      for (String value : values) {
        result.add(new HarNameValuePair(name, value));
      }
    }
    return result;
  }

  private BodyProcessingResult processBody(byte[] body, boolean isRequest, String contentType) {
    if (body == null || body.length == 0) {
      return new BodyProcessingResult("", "");
    }

    if (isBinary(body)) {
      return new BodyProcessingResult(Base64.getEncoder().encodeToString(body), "base64");
    }

    String text = new String(body, StandardCharsets.UTF_8);
    String trimmed = text.trim();
    String lowerContentType = contentType == null ? "" : contentType.toLowerCase();
    boolean looksJson =
        lowerContentType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[");
    if (looksJson) {
      String redacted = redactJson(text);
      if (!redacted.isEmpty()) {
        return new BodyProcessingResult(redacted, "");
      }
    }

    text = redactSecretValue(text);
    return new BodyProcessingResult(text, "");
  }

  private String redactJson(String json) {
    try {
      JsonNode root = mapper.readTree(json);
      JsonNode redacted = redactJsonNode(root);
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(redacted);
    } catch (IOException e) {
      return "";
    }
  }

  private JsonNode redactJsonNode(JsonNode node) {
    if (node.isObject()) {
      ObjectNode obj = mapper.createObjectNode();
      node.fields()
          .forEachRemaining(
              entry -> {
                String key = entry.getKey();
                if (isSensitiveJsonKey(key)) {
                  obj.set(key, mapper.getNodeFactory().textNode(getRedactionLabel(key)));
                } else {
                  obj.set(key, redactJsonNode(entry.getValue()));
                }
              });
      return obj;
    }

    if (node.isArray()) {
      ArrayNode arr = mapper.createArrayNode();
      for (JsonNode item : node) {
        arr.add(redactJsonNode(item));
      }
      return arr;
    }

    if (node.isTextual()) {
      String val = node.asText();
      if (looksLikeToken(val)) {
        return mapper.getNodeFactory().textNode("[REDACTED_TOKEN]");
      }
      return node;
    }

    return node;
  }

  private boolean isSensitiveJsonKey(String key) {
    if (key == null) {
      return false;
    }
    String lower = key.toLowerCase();
    for (String sensitive : SENSITIVE_JSON_KEYS) {
      if (lower.contains(sensitive)) {
        return true;
      }
    }
    return false;
  }

  private String getRedactionLabel(String key) {
    String lower = key.toLowerCase();
    if (lower.contains("bearer")) {
      return "[REDACTED_BEARER_TOKEN]";
    }
    if (lower.contains("apikey") || lower.contains("api_key") || lower.contains("api-key")) {
      return "[REDACTED_API_KEY]";
    }
    if (lower.contains("password")) {
      return "[REDACTED_PASSWORD]";
    }
    if (lower.contains("secret")) {
      return "[REDACTED_SECRET]";
    }
    if (lower.contains("iam_token") || lower.contains("iam-token")) {
      return "[REDACTED_IAM_TOKEN]";
    }
    if (lower.contains("access_token") || lower.contains("access-token")) {
      return "[REDACTED_ACCESS_TOKEN]";
    }
    if (lower.contains("session")) {
      return "[REDACTED_SESSION_TOKEN]";
    }
    if (lower.contains("cookie")) {
      return "[REDACTED_COOKIE]";
    }
    return "[REDACTED_TOKEN]";
  }

  private boolean looksLikeToken(String value) {
    if (value == null) {
      return false;
    }
    if (value.length() > 32 && TOKEN_LIKE_PATTERN.matcher(value).matches()) {
      return true;
    }
    return value.length() > 50 && value.chars().filter(ch -> ch == '.').count() == 2;
  }

  private String redactSecretValue(String value) {
    if (value == null) {
      return null;
    }
    String redacted = value;
    redacted = BEARER_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_BEARER_TOKEN]");
    redacted = BASIC_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_BASIC_AUTH]");
    redacted = APIKEY_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_API_KEY]");
    redacted = IAM_TOKEN_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_IAM_TOKEN]");
    redacted = ACCESS_TOKEN_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_ACCESS_TOKEN]");
    redacted = SESSION_TOKEN_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_SESSION_TOKEN]");
    redacted = TOKEN_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_TOKEN]");
    redacted = PASSWORD_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_PASSWORD]");
    redacted = SECRET_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_SECRET]");
    redacted = COOKIE_PATTERN.matcher(redacted).replaceAll("=[REDACTED_COOKIE]$2");
    if (looksLikeToken(redacted)) {
      redacted = "[REDACTED_TOKEN]";
    }
    return redacted;
  }

  private boolean isBinary(byte[] data) {
    if (data == null || data.length == 0) {
      return false;
    }
    int sample = Math.min(data.length, MAX_SAMPLE);
    int nonPrintable = 0;
    for (int i = 0; i < sample; i++) {
      byte b = data[i];
      if (b == 9 || b == 10 || b == 13) {
        continue;
      }
      if (b < 32 || b > 126) {
        nonPrintable++;
      }
    }
    double ratio = (double) nonPrintable / (double) sample;
    return ratio > BINARY_THRESHOLD;
  }

  private boolean isSensitiveHeader(String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase();
    for (String pattern : SENSITIVE_HEADER_PATTERNS) {
      if (lower.contains(pattern)) {
        return true;
      }
    }
    return false;
  }

  private String getHttpVersion(String proto) {
    if (proto == null || proto.isEmpty()) {
      return "HTTP/1.1";
    }
    return proto;
  }

  private int getStatusCode(HarEntryInput input) {
    if (input.getStatusCode() != null) {
      return input.getStatusCode();
    }
    return input.getCallError() != null ? 0 : -1;
  }

  private String getStatusText(HarEntryInput input) {
    if (input.getStatusText() != null) {
      return input.getStatusText();
    }
    return input.getCallError() != null ? input.getCallError() : "";
  }

  private HarArchive readOrCreateHar() {
    if (harPath == null) {
      return createNewArchive();
    }
    try {
      if (!Files.exists(harPath) || Files.size(harPath) == 0) {
        return createNewArchive();
      }
      byte[] data = Files.readAllBytes(harPath);
      return mapper.readValue(data, HarArchive.class);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to parse existing HAR file, creating new", e);
      return createNewArchive();
    }
  }

  private HarArchive createNewArchive() {
    HarArchive archive = new HarArchive();
    HarLog log = archive.getLog();
    log.setVersion("1.2");
    HarLog.Creator creator = new HarLog.Creator();
    creator.setName("ibm-java-sdk-core");
    creator.setVersion("1.0.0");
    log.setCreator(creator);
    log.setPages(new ArrayList<>());
    log.setEntries(new ArrayList<>());
    return archive;
  }

  private void writeHar(HarArchive archive) {
    try {
      if (harPath.getParent() != null) {
        Files.createDirectories(harPath.getParent());
      }
      byte[] data = mapper.writeValueAsBytes(archive);
      Files.write(harPath, data);
      trySetPermissions();
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Failed to write HAR file", e);
    }
  }

  private void rotateHarFile() {
    try {
      String timestamp =
          DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
      String base = harPath.getFileName().toString();
      if (base.endsWith(".har")) {
        base = base.substring(0, base.length() - 4);
      }
      Path backup = harPath.resolveSibling(base + "_" + timestamp + ".har");
      Files.move(harPath, backup, StandardCopyOption.REPLACE_EXISTING);
      LOG.info("Rotated HAR file to: " + backup);
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Failed to rotate HAR file", e);
    }
  }

  private void trySetPermissions() {
    try {
      Set<PosixFilePermission> perms = new HashSet<>();
      perms.add(PosixFilePermission.OWNER_READ);
      perms.add(PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(harPath, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Best effort; ignore on unsupported platforms.
    }
  }

  private String getHeaderValue(Map<String, List<String>> headers, String name) {
    if (headers == null || name == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
        return entry.getValue().get(0);
      }
    }
    return null;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static class BodyProcessingResult {
    final String text;
    final String encoding;

    BodyProcessingResult(String text, String encoding) {
      this.text = text;
      this.encoding = encoding;
    }
  }

  public static class HarEntryInput {
    private String requestUrl;
    private String method;
    private Map<String, List<String>> requestHeaders = new HashMap<>();
    private Map<String, List<String>> queryParams = new HashMap<>();
    private byte[] requestBody;
    private String requestContentType = "";
    private String requestProtocol = "HTTP/1.1";

    private Integer statusCode;
    private String statusText;
    private String responseProtocol = "HTTP/1.1";
    private Map<String, List<String>> responseHeaders = new HashMap<>();
    private byte[] responseBody;
    private String responseContentType = "";
    private String callError;

    private Instant startTime = Instant.now();
    private Instant endTime = Instant.now();

    public String getRequestUrl() {
      return requestUrl;
    }

    public void setRequestUrl(String requestUrl) {
      this.requestUrl = requestUrl;
    }

    public String getMethod() {
      return method;
    }

    public void setMethod(String method) {
      this.method = method;
    }

    public Map<String, List<String>> getRequestHeaders() {
      return requestHeaders;
    }

    public void setRequestHeaders(Map<String, List<String>> requestHeaders) {
      this.requestHeaders = requestHeaders;
    }

    public Map<String, List<String>> getQueryParams() {
      return queryParams;
    }

    public void setQueryParams(Map<String, List<String>> queryParams) {
      this.queryParams = queryParams;
    }

    public byte[] getRequestBody() {
      return requestBody;
    }

    public void setRequestBody(byte[] requestBody) {
      this.requestBody = requestBody;
    }

    public String getRequestContentType() {
      return requestContentType;
    }

    public void setRequestContentType(String requestContentType) {
      this.requestContentType = requestContentType;
    }

    public String getRequestProtocol() {
      return requestProtocol;
    }

    public void setRequestProtocol(String requestProtocol) {
      this.requestProtocol = requestProtocol;
    }

    public Integer getStatusCode() {
      return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
      this.statusCode = statusCode;
    }

    public String getStatusText() {
      return statusText;
    }

    public void setStatusText(String statusText) {
      this.statusText = statusText;
    }

    public String getResponseProtocol() {
      return responseProtocol;
    }

    public void setResponseProtocol(String responseProtocol) {
      this.responseProtocol = responseProtocol;
    }

    public Map<String, List<String>> getResponseHeaders() {
      return responseHeaders;
    }

    public void setResponseHeaders(Map<String, List<String>> responseHeaders) {
      this.responseHeaders = responseHeaders;
    }

    public byte[] getResponseBody() {
      return responseBody;
    }

    public void setResponseBody(byte[] responseBody) {
      this.responseBody = responseBody;
    }

    public String getResponseContentType() {
      return responseContentType;
    }

    public void setResponseContentType(String responseContentType) {
      this.responseContentType = responseContentType;
    }

    public String getCallError() {
      return callError;
    }

    public void setCallError(String callError) {
      this.callError = callError;
    }

    public Instant getStartTime() {
      return startTime;
    }

    public void setStartTime(Instant startTime) {
      this.startTime = startTime;
    }

    public Instant getEndTime() {
      return endTime;
    }

    public void setEndTime(Instant endTime) {
      this.endTime = endTime;
    }
  }
}

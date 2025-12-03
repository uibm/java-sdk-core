package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarResponse {
  private int status;
  private String statusText;
  private String httpVersion;
  private List<HarNameValuePair> headers = new ArrayList<>();
  private HarContent content;
  private String redirectURL;
  private long headersSize;
  private long bodySize;

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public String getStatusText() {
    return statusText;
  }

  public void setStatusText(String statusText) {
    this.statusText = statusText;
  }

  public String getHttpVersion() {
    return httpVersion;
  }

  public void setHttpVersion(String httpVersion) {
    this.httpVersion = httpVersion;
  }

  public List<HarNameValuePair> getHeaders() {
    return headers;
  }

  public void setHeaders(List<HarNameValuePair> headers) {
    this.headers = headers;
  }

  public HarContent getContent() {
    return content;
  }

  public void setContent(HarContent content) {
    this.content = content;
  }

  public String getRedirectURL() {
    return redirectURL;
  }

  public void setRedirectURL(String redirectURL) {
    this.redirectURL = redirectURL;
  }

  public long getHeadersSize() {
    return headersSize;
  }

  public void setHeadersSize(long headersSize) {
    this.headersSize = headersSize;
  }

  public long getBodySize() {
    return bodySize;
  }

  public void setBodySize(long bodySize) {
    this.bodySize = bodySize;
  }
}

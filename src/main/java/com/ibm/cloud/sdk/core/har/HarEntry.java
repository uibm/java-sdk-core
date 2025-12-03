package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarEntry {
  private String pageref;
  private OffsetDateTime startedDateTime;
  private double time;
  private HarRequest request;
  private HarResponse response;
  private Object cache = new Object();
  private HarTimings timings;
  private String serverIPAddress;
  private String connection;

  public String getPageref() {
    return pageref;
  }

  public void setPageref(String pageref) {
    this.pageref = pageref;
  }

  public OffsetDateTime getStartedDateTime() {
    return startedDateTime;
  }

  public void setStartedDateTime(OffsetDateTime startedDateTime) {
    this.startedDateTime = startedDateTime;
  }

  public double getTime() {
    return time;
  }

  public void setTime(double time) {
    this.time = time;
  }

  public HarRequest getRequest() {
    return request;
  }

  public void setRequest(HarRequest request) {
    this.request = request;
  }

  public HarResponse getResponse() {
    return response;
  }

  public void setResponse(HarResponse response) {
    this.response = response;
  }

  public Object getCache() {
    return cache;
  }

  public void setCache(Object cache) {
    this.cache = cache;
  }

  public HarTimings getTimings() {
    return timings;
  }

  public void setTimings(HarTimings timings) {
    this.timings = timings;
  }

  public String getServerIPAddress() {
    return serverIPAddress;
  }

  public void setServerIPAddress(String serverIPAddress) {
    this.serverIPAddress = serverIPAddress;
  }

  public String getConnection() {
    return connection;
  }

  public void setConnection(String connection) {
    this.connection = connection;
  }
}

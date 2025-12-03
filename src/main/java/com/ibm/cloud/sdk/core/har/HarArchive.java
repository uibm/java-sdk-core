package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarArchive {
  private HarLog log = new HarLog();

  public HarLog getLog() {
    return log;
  }

  public void setLog(HarLog log) {
    this.log = log;
  }
}

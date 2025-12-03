package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarTimings {
  private double send;
  private double wait;
  private double receive;

  public double getSend() {
    return send;
  }

  public void setSend(double send) {
    this.send = send;
  }

  public double getWait() {
    return wait;
  }

  public void setWait(double wait) {
    this.wait = wait;
  }

  public double getReceive() {
    return receive;
  }

  public void setReceive(double receive) {
    this.receive = receive;
  }
}

package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarLog {
  private String version;
  private Creator creator = new Creator();
  private List<Object> pages = new ArrayList<>();
  private List<HarEntry> entries = new ArrayList<>();

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public Creator getCreator() {
    return creator;
  }

  public void setCreator(Creator creator) {
    this.creator = creator;
  }

  public List<Object> getPages() {
    return pages;
  }

  public void setPages(List<Object> pages) {
    this.pages = pages;
  }

  public List<HarEntry> getEntries() {
    return entries;
  }

  public void setEntries(List<HarEntry> entries) {
    this.entries = entries;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Creator {
    private String name;
    private String version;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
    }
  }
}

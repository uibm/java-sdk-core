package com.ibm.cloud.sdk.core.har;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * HAR postData object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarPostData {
  private String mimeType;
  private String text;
  private List<HarNameValuePair> params = new ArrayList<>();

  public String getMimeType() {
    return mimeType;
  }

  public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public List<HarNameValuePair> getParams() {
    return params;
  }

  public void setParams(List<HarNameValuePair> params) {
    this.params = params;
  }
}

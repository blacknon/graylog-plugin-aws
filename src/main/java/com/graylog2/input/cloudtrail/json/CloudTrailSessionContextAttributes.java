package com.graylog2.input.cloudtrail.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudTrailSessionContextAttributes {
    @JsonProperty("mfaAuthenticated")
    public String mfaAuthenticated;
    @JsonProperty("creationDate")
    public String creationDate;

}

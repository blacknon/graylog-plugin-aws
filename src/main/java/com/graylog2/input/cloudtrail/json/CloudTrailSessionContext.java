package com.graylog2.input.cloudtrail.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudTrailSessionContext {
    @JsonProperty("attributes")
    public CloudTrailSessionContextAttributes attributes;

}

package com.forter.storm.apis;

import com.forter.storm.apis.errors.ApiTopologyErrorHandler;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Configuration for APIs stream construction
 */
public class ApisTopologyConfig implements Serializable {
    String apiSpout;
    List<String> defaultStreamSpouts;
    Map<String, String> componentReplace;
    String apisStreamName;
    ApiTopologyErrorHandler errorHandler;
    String apisCommandFieldName;
    String apisIdFieldName;

    protected ApisTopologyConfig() {}

    /**
     * Create a new API instrumentation bolt wrapper
     * @param bolt the bolt to wrap
     * @param isExcluded is the bolt statically excluded from API stream topology
     * @param defaultStreamSpouts a list of spouts that emit into the default stream. This is assuming they all share
     *                            the same output fields (pretty fair assumption)
     * @param apiSpout the spout that emits into API topology. The wrapper will know to swap its emits with the raw
     *                 input specified in the command
     */

    public String getApiSpout() {
        return apiSpout;
    }

    public List<String> getDefaultStreamSpouts() {
        return defaultStreamSpouts;
    }

    public Map<String, String> getComponentReplace() {
        return componentReplace;
    }

    public String getApisStreamName() {
        return apisStreamName;
    }

    public ApiTopologyErrorHandler getErrorHandler() {
        return errorHandler;
    }

    public String getApisCommandFieldName() {
        return apisCommandFieldName;
    }

    public String getApisIdFieldName() {
        return apisIdFieldName;
    }

}

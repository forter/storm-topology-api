package com.forter.storm.apis.wrappers;

import backtype.storm.task.IOutputCollector;
import backtype.storm.task.OutputCollector;
import backtype.storm.tuple.Tuple;
import com.forter.storm.apis.ApisRemoteCommandTopologyConfig;
import com.forter.storm.apis.ApisTopologyCommand;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
* Output collector interceptor. Used to route default stream emits to the API stream.
*/
public class ApisInterceptorOutputCollector extends OutputCollector {
    private static Logger logger = LoggerFactory.getLogger(ApisInterceptorOutputCollector.class);

    /**
     * Map that maps morphed tuple to its original API stream tuple. It's not emptied automatically since we never know
     * when a bolt is going to ack/fail/emit again...
     */
    private final ConcurrentMap<Tuple, Tuple> messageOriginalTupleMap;
    private final ApisRemoteCommandTopologyConfig apisConfiguration;
    private final String apisIdFieldName;
    private final String apisCommandFieldName;
    private final boolean apiAware;
    private final String id;

    public ApisInterceptorOutputCollector(IOutputCollector delegate, ApisRemoteCommandTopologyConfig apisConfiguration, boolean apiAware, String id) {
        super(delegate);
        this.apiAware = apiAware;
        this.messageOriginalTupleMap = new ConcurrentLinkedHashMap.Builder<Tuple, Tuple>()
                .maximumWeightedCapacity(Integer.getInteger("apis.output.intercept.tuple.capacity", 500))
                .build();
        this.apisIdFieldName = apisConfiguration.getApisIdFieldName();
        this.apisCommandFieldName = apisConfiguration.getApisCommandFieldName();
        this.apisConfiguration = apisConfiguration;
        this.id = id;
    }

    /**
     * We intercept the call to emit in order to find what true tuples (ones that were passed in API stream) need to
     * be emitted (if any). If we find them, we will ignore the default stream emit since it's a fake. If not, we send
     * this emit to super.
     */
    @Override
    public List<Integer> emit(String streamId, Collection<Tuple> anchors, List<Object> tuple) {
        // if the wrapped bolt emitted to default stream and has anchored its tuples (prerequisites for using API interceptor)
        if (!apisConfiguration.isApiStream(streamId) && anchors != null && !anchors.isEmpty()) {
            // find all anchors that need to be linked to the emission. This will happen for all emits in the default flow too
            Iterable<Tuple> anchorsToIntercept = Iterables.filter(anchors,
                    new Predicate<Tuple>() {
                        @Override
                        public boolean apply(Tuple tuple) {
                            return messageOriginalTupleMap.containsKey(tuple);
                        }
                    });

            if (!Iterables.isEmpty(anchorsToIntercept)) { // empty means tuples were not API instruments
                InterceptedAnchorResult interceptResult = getInterceptedAnchors(anchorsToIntercept);

                if (interceptResult.command != null) {
                    Preconditions.checkArgument(!interceptResult.apiAnchors.isEmpty());
                    ArrayList<Object> apiEmissionTuple = Lists.newArrayList(interceptResult.id, interceptResult.command);
                    apiEmissionTuple.addAll(tuple);
                    return super.emit(apisConfiguration.getApisStreamName(streamId), interceptResult.apiAnchors, apiEmissionTuple);
                }

                throw new RuntimeException("getInterceptedAnchors returned no API command, while " +
                        "anchorsToIntercept was not empty. This is not supposed to happen.");
            }
        }
        return super.emit(streamId, anchors, tuple);
    }

    /**
     * if the execute was generated by an API call, we shouldn't ack the input, but rather ack the original tuple that
     * the API stream received.
     */
    @Override
    public void ack(Tuple input) {
        if (!apisConfiguration.isApiStream(input.getSourceStreamId())) {
            Tuple t = this.messageOriginalTupleMap.get(input);
            if (t != null) {
                super.ack(t);
                return;
            }
        }
        super.ack(input);
    }

    /**
     * if the execute was generated by an API call, we shouldn't fail the input, but rather fail the original tuple that
     * the API stream received.
     */
    @Override
    public void fail(Tuple input) {
        if (!apisConfiguration.isApiStream(input.getSourceStreamId())  || apiAware) {
            Tuple t = this.messageOriginalTupleMap.get(input);
            if (t != null) {
                super.ack(t); // API stream tuples are always acked since failure is meaningless in the context of APIs
                return;
            }
        }
        super.fail(input);
    }

    /**
     * Adds the original API tuple to the bolt's expected emissions. This means that when the morphed tuple is in the
     * emission's anchors, the emit will be dropped and the API command would be updated with the bolt's emission values
     * instead.
     * @param originalTuple the tuple that was received on the API stream
     * @param morphedTuple the tuple that should be passed to default stream bolt
     */
    public void addEmissionInterception(Tuple originalTuple, Tuple morphedTuple) {
        messageOriginalTupleMap.put(morphedTuple, originalTuple);
    }

    /**
     * this method gets the list of anchors that are present in the messageOriginalTupleMap map. That indicates that
     * they were generated by the APIs bolt wrapper, and need to be linked to their original (API stream) tuples.
     * @param anchorsToIntercept a list of default stream tuples (morphed tuples)
     * @return the API command that is linked to all the original tuples, null if not such tuples were found
     */
    private InterceptedAnchorResult getInterceptedAnchors(Iterable<Tuple> anchorsToIntercept) {
        ApisTopologyCommand command = null;
        Object id = null;
        List<Tuple> apiAnchors = Lists.newArrayList();
        for (Tuple anchor : anchorsToIntercept) {
            Tuple apiStreamTuple = messageOriginalTupleMap.get(anchor);
            Preconditions.checkNotNull(apiStreamTuple);

            ApisTopologyCommand apisTopologyCommand =
                    (ApisTopologyCommand) apiStreamTuple.getValueByField(apisCommandFieldName);

            Object apisRequestId =
                    apiStreamTuple.getValueByField(apisIdFieldName);

            if (command == null) {
                command = apisTopologyCommand;
                id = apisRequestId;
            } else {
                Preconditions.checkArgument(command == apisTopologyCommand,
                        "All tuples anchored by single execute must be associated with the same API command instance");
                Preconditions.checkArgument(id.equals(apisRequestId),
                        "All tuples anchored by single execute must be associated with the same API command ID");
            }

            apiAnchors.add(apiStreamTuple);
        }
        return new InterceptedAnchorResult(id, command, apiAnchors);
    }

    private class InterceptedAnchorResult {
        private final Object id;
        private final ApisTopologyCommand command;
        private final List<Tuple> apiAnchors;

        public InterceptedAnchorResult(Object id, ApisTopologyCommand command, List<Tuple> apiAnchors) {
            this.id = id;
            this.command = command;
            this.apiAnchors = apiAnchors;
        }
    }
}

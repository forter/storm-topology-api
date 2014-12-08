package com.forter.storm.apis.bolt;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.utils.Utils;
import com.forter.storm.apis.ApisTopologyCommand;
import com.forter.storm.apis.ApisTopologyConfig;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Bolt that is used solely for un-anchoring tuples for the rest of the topology
 */
public class ApisAwareTupleUnanchoringBolt implements IRichBolt, ApiAware<ApisTopologyCommand> {
    private final String[] outFields;
    private OutputCollector collector;

    public ApisAwareTupleUnanchoringBolt(List<String> outFieldsList) {
        String[] outFields = new String[outFieldsList.size()];
        this.outFields = outFieldsList.toArray(outFields);
    }

    public ApisAwareTupleUnanchoringBolt(String[] outFields) {
        this.outFields = outFields;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(Tuple input) {
        this.collector.emit(input.select(new Fields(this.outFields)));
        this.collector.ack(input);
    }

    @Override
    public void execute(Tuple input, ApisTopologyCommand command) {
        List<Object> tuple = getApisOutTuple(input);
        this.collector.emit(input.getSourceStreamId(), input, tuple);
        this.collector.ack(input);
    }

    private List<Object> getApisOutTuple(Tuple input) {
        List<Object> tuple = Lists.newArrayList();

        tuple.add(input.getValue(0));
        tuple.add(input.getValue(1));

        Iterables.addAll(tuple, input.select(new Fields(this.outFields)));

        return tuple;
    }

    @Override
    public void cleanup() { }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields(outFields));
    }

    @Override
    public Map<String, Object> getComponentConfiguration() { return null; }

    @Override
    public void setApiConfiguration(ApisTopologyConfig apisConfiguration) {}
}

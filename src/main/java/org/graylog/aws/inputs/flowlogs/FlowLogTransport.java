package org.graylog.aws.inputs.flowlogs;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.codahale.metrics.MetricSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import org.graylog.aws.config.AWSPluginConfiguration;
import org.graylog2.plugin.LocalMetricRegistry;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.cluster.ClusterConfigService;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.CodecAggregator;
import org.graylog2.plugin.inputs.transports.ThrottleableTransport;
import org.graylog2.plugin.inputs.transports.Transport;
import org.graylog2.plugin.lifecycles.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FlowLogTransport implements Transport {
    private static final Logger LOG = LoggerFactory.getLogger(FlowLogTransport.class);
    public static final String NAME = "flowlog";

    private static final String CK_AWS_REGION = "aws_region";
    private static final String CK_LOG_GROUP_NAME = "log_group_name";
    private static final String CK_MAX_BACKTRACK = "max_backtrack";
    private static final String CK_DELAY_MINUTES = "read_delay_minutes";

    private AtomicBoolean paused;

    private final ServerStatus serverStatus;
    private final LocalMetricRegistry localRegistry;
    private final ClusterConfigService clusterConfigService;

    @AssistedInject
    public FlowLogTransport(@Assisted final Configuration configuration,
                               final ClusterConfigService clusterConfigService,
                               final EventBus serverEventBus,
                               final ServerStatus serverStatus,
                               LocalMetricRegistry localRegistry) {
        this.clusterConfigService = clusterConfigService;
        this.serverStatus = serverStatus;
        this.localRegistry = localRegistry;

        this.paused = new AtomicBoolean(true);
    }

    @Override
    public void launch(MessageInput input) throws MisfireException {
        serverStatus.awaitRunning(new Runnable() {
            @Override
            public void run() {
                lifecycleStateChange(Lifecycle.RUNNING);
            }
        });

        AWSPluginConfiguration config = clusterConfigService.get(AWSPluginConfiguration.class);

        // Ready to run.
        paused.set(false);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("aws-flowlog-reader-%d")
                        .setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                            @Override
                            public void uncaughtException(Thread t, Throwable e) {
                                LOG.error("Uncaught exception in AWS FlowLogs reader.", e);
                            }
                        })
                        .build()
        );
        
        FlowLogReader reader = new FlowLogReader(
                Region.getRegion(Regions.fromName(input.getConfiguration().getString(CK_AWS_REGION))),
                input.getConfiguration().getString(CK_LOG_GROUP_NAME),
                input,
                input.getConfiguration().getInt(CK_MAX_BACKTRACK),
                input.getConfiguration().getInt(CK_DELAY_MINUTES),
                paused,
                clusterConfigService
        );

        // Run with 10s delay between complete executions.
        executor.scheduleWithFixedDelay(reader, 0, 10, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        // NEIN
    }

    @Subscribe
    public void lifecycleStateChange(Lifecycle lifecycle) {
        LOG.debug("Lifecycle changed to {}", lifecycle);
        switch (lifecycle) {
            case PAUSED:
            case FAILED:
            case HALTING:
                // Pause executor
                paused.set(true);
                break;
            default:
                // Start executor
                paused.set(false);
                break;
        }
    }

    @Override
    public void setMessageAggregator(CodecAggregator aggregator) {
        // Not supported.
    }

    @Override
    public MetricSet getMetricSet() {
        return localRegistry;
    }

    @FactoryClass
    public interface Factory extends Transport.Factory<FlowLogTransport> {
        @Override
        FlowLogTransport create(Configuration configuration);

        @Override
        Config getConfig();
    }

    @ConfigClass
    public static class Config extends ThrottleableTransport.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest r = super.getRequestedConfiguration();

            Map<String, String> regions = Maps.newHashMap();
            for (Regions region : Regions.values()) {
                regions.put(region.getName(), region.toString());
            }

            r.addField(new DropdownField(
                    CK_AWS_REGION,
                    "AWS Region",
                    Regions.US_EAST_1.getName(),
                    regions,
                    "The AWS region the FlowLogs are stored in.",
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));

            r.addField(new TextField(
                    CK_LOG_GROUP_NAME,
                    "Log group name",
                    "",
                    "The CloudWatch log group name that the flow logs are being written to. (Will read all flow log streams in this group)",
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));

            r.addField(new NumberField(
                    CK_MAX_BACKTRACK,
                    "Maximum history backtrack (hours)",
                    12,
                    "If the input (or Graylog) is not running for a while, it will try to read all available data since the last time it had " +
                            "successfully read something. This parameter controls how many maximum hours it will go back in time to avoid excessive " +
                            "data transfer and processing in case of an input that was not running for a long time.",
                    ConfigurationField.Optional.NOT_OPTIONAL,
                    NumberField.Attribute.ONLY_POSITIVE
            ));

            r.addField(new NumberField(
                    CK_DELAY_MINUTES,
                    "Read delay (minutes)",
                    15,
                    "AWS does not write FlowLogs in real-time but is always a few minutes behind. You can see the delay by comparing the log timestamp " +
                            "to the actual time and 15 minutes is a good default value. You will not receive any FlowLogs if this parameter is set too low " +
                            "and usually it requires no change from the default value. This has only been made configurable for the case that AWS is getting " +
                            "close to real-time logs in the future. Do not touch it until that happens.",
                    ConfigurationField.Optional.NOT_OPTIONAL,
                    NumberField.Attribute.ONLY_POSITIVE
            ));

            return r;
        }
    }

}

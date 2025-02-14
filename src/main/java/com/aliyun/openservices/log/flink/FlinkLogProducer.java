package com.aliyun.openservices.log.flink;

import com.aliyun.openservices.aliyun.log.producer.LogProducer;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.ProducerConfig;
import com.aliyun.openservices.aliyun.log.producer.ProjectConfig;
import com.aliyun.openservices.aliyun.log.producer.Result;
import com.aliyun.openservices.aliyun.log.producer.errors.ProducerException;
import com.aliyun.openservices.log.flink.data.RawLog;
import com.aliyun.openservices.log.flink.data.RawLogGroup;
import com.aliyun.openservices.log.flink.util.ConfigParser;
import com.aliyun.openservices.log.flink.model.LogSerializationSchema;
import com.shade.aliyun.openservices.log.common.LogItem;
import com.shade.google.common.util.concurrent.FutureCallback;
import com.shade.google.common.util.concurrent.Futures;
import com.shade.google.common.util.concurrent.ListenableFuture;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.aliyun.openservices.log.flink.ConfigConstants.*;

public class FlinkLogProducer<T> extends RichSinkFunction<T> implements CheckpointedFunction {

    private static final long DEFAULT_FLUSH_TIMEOUT_MS = 10000;

    private static final Logger LOG = LoggerFactory.getLogger(FlinkLogProducer.class);
    private final LogSerializationSchema<T> schema;
    private LogPartitioner<T> customPartitioner = null;
    private transient Producer producer;
    private transient ProducerCallback callback;
    private final String project;
    private final String logstore;
    private ExecutorService executor;
    private final AtomicLong buffered = new AtomicLong(0);
    private final long flushTimeoutMs;
    private final ConfigParser configParser;

    public FlinkLogProducer(final LogSerializationSchema<T> schema, Properties configProps) {
        if (schema == null) {
            throw new IllegalArgumentException("schema cannot be null");
        }
        if (configProps == null) {
            throw new IllegalArgumentException("configProps cannot be null");
        }
        this.schema = schema;
        this.configParser = new ConfigParser(configProps);
        this.project = configParser.getString(ConfigConstants.LOG_PROJECT);
        this.logstore = configParser.getString(ConfigConstants.LOG_LOGSTORE);
        this.flushTimeoutMs = configParser.getLong(PRODUCER_FLUSH_TIMEOUT_MS, DEFAULT_FLUSH_TIMEOUT_MS);
    }

    public void setCustomPartitioner(LogPartitioner<T> customPartitioner) {
        this.customPartitioner = customPartitioner;
    }

    private Producer createProducer(ConfigParser parser) {
        ProducerConfig producerConfig = new ProducerConfig();
        producerConfig.setLingerMs(parser.getInt(FLUSH_INTERVAL_MS, ProducerConfig.DEFAULT_LINGER_MS));
        producerConfig.setRetries(parser.getInt(MAX_RETRIES, ProducerConfig.DEFAULT_RETRIES));
        producerConfig.setBaseRetryBackoffMs(
                parser.getLong(BASE_RETRY_BACK_OFF_TIME_MS, ProducerConfig.DEFAULT_BASE_RETRY_BACKOFF_MS));
        producerConfig.setMaxRetryBackoffMs(
                parser.getLong(MAX_RETRY_BACK_OFF_TIME_MS, ProducerConfig.DEFAULT_MAX_RETRY_BACKOFF_MS));
        producerConfig.setMaxBlockMs(
                parser.getLong(MAX_BLOCK_TIME_MS, ProducerConfig.DEFAULT_MAX_BLOCK_MS));
        producerConfig.setIoThreadCount(parser.getInt(IO_THREAD_NUM, ProducerConfig.DEFAULT_IO_THREAD_COUNT));
        producerConfig.setBuckets(parser.getInt(BUCKETS, ProducerConfig.DEFAULT_BUCKETS));
        producerConfig.setTotalSizeInBytes(parser.getInt(TOTAL_SIZE_IN_BYTES, ProducerConfig.DEFAULT_TOTAL_SIZE_IN_BYTES));
        producerConfig.setAdjustShardHash(parser.getBool(PRODUCER_ADJUST_SHARD_HASH, true));
        Producer producer = new LogProducer(producerConfig);
        ProjectConfig config = new ProjectConfig(project,
                parser.getString(ConfigConstants.LOG_ENDPOINT),
                parser.getString(ConfigConstants.LOG_ACCESSKEYID),
                parser.getString(ConfigConstants.LOG_ACCESSKEY));
        producer.putProjectConfig(config);
        return producer;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        if (callback == null) {
            callback = new ProducerCallback(buffered);
        }
        if (customPartitioner != null) {
            customPartitioner.initialize(
                    getRuntimeContext().getIndexOfThisSubtask(),
                    getRuntimeContext().getNumberOfParallelSubtasks());
        }
        if (producer == null) {
            producer = createProducer(configParser);
        }
        executor = Executors.newSingleThreadExecutor();
    }

    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        if (producer == null) {
            return;
        }
        long beginAt = System.currentTimeMillis();
        while (true) {
            long usedTime = System.currentTimeMillis() - beginAt;
            if (buffered.get() <= 0) {
                LOG.info("snapshotState succeed, usedTime={}", usedTime);
                return;
            }
            if (usedTime >= flushTimeoutMs) {
                LOG.warn("Wait snapshotState timeout, timeout={}", flushTimeoutMs);
                break;
            }
            LOG.info("Sleep 100 ms to wait all records flushed");
            Thread.sleep(100);
        }
    }

    @Override
    public void initializeState(FunctionInitializationContext functionInitializationContext) throws Exception {
    }

    @Override
    public void invoke(T value, Context context) {
        if (this.producer == null) {
            throw new IllegalStateException("Flink log producer has not been initialized yet!");
        }
        RawLogGroup logGroup = schema.serialize(value);
        if (logGroup == null) {
            LOG.info("The serialized log group is null, will not send any data to log service");
            return;
        }
        String shardHashKey = null;
        if (customPartitioner != null) {
            shardHashKey = customPartitioner.getHashKey(value);
        }
        List<LogItem> logs = new ArrayList<>();
        for (RawLog rawLog : logGroup.getLogs()) {
            if (rawLog == null) {
                continue;
            }
            LogItem record = new LogItem(rawLog.getTime());
            for (Map.Entry<String, String> kv : rawLog.getContents().entrySet()) {
                if (kv.getKey() != null) {
                    record.PushBack(kv.getKey(), kv.getValue());
                }
            }
            logs.add(record);
        }
        if (logs.isEmpty()) {
            return;
        }
        try {
            ListenableFuture<Result> future = producer.send(project,
                    logstore,
                    logGroup.getTopic(),
                    logGroup.getSource(),
                    shardHashKey,
                    logs);
            Futures.addCallback(future, callback, executor);
            buffered.incrementAndGet();
        } catch (InterruptedException | ProducerException e) {
            LOG.error("Error while sending logs", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        if (producer != null) {
            producer.close();
            producer = null;
        }
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOG.warn("Stop executor failed");
                }
            }
        }
        super.close();
        LOG.info("Flink log producer has been closed");
    }

    public static class ProducerCallback implements FutureCallback<Result> {
        private AtomicLong count;

        ProducerCallback(AtomicLong count) {
            this.count = count;
        }

        @Override
        public void onSuccess(@Nullable Result result) {
            count.decrementAndGet();
            if (result != null && !result.isSuccessful()) {
                LOG.error("Send logs failed, code={}, errorMsg={}, retries={}",
                        result.getErrorCode(),
                        result.getErrorMessage(),
                        result.getAttemptCount());
            }
        }

        @Override
        public void onFailure(Throwable throwable) {
            count.decrementAndGet();
            LOG.error("Send logs failed", throwable);
        }
    }
}
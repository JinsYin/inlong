/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.singletenant.flink.cdc.mysql.debezium.task;

import static org.apache.inlong.sort.singletenant.flink.cdc.mysql.source.offset.BinlogOffset.NO_STOPPING_OFFSET;
import static org.apache.inlong.sort.singletenant.flink.cdc.mysql.source.utils.RecordUtils.getBinlogPosition;

import com.github.shyiko.mysql.binlog.event.Event;
import io.debezium.DebeziumException;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.connector.mysql.MySqlOffsetContext;
import io.debezium.connector.mysql.MySqlStreamingChangeEventSource;
import io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics;
import io.debezium.connector.mysql.MySqlTaskContext;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;
import org.apache.inlong.sort.singletenant.flink.cdc.mysql.debezium.dispatcher.EventDispatcherImpl;
import org.apache.inlong.sort.singletenant.flink.cdc.mysql.debezium.dispatcher.SignalEventDispatcher;
import org.apache.inlong.sort.singletenant.flink.cdc.mysql.debezium.reader.SnapshotSplitReader.SnapshotBinlogSplitChangeEventSourceContextImpl;
import org.apache.inlong.sort.singletenant.flink.cdc.mysql.source.offset.BinlogOffset;
import org.apache.inlong.sort.singletenant.flink.cdc.mysql.source.split.MySqlBinlogSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Task to read all binlog for table and also supports read bounded (from lowWatermark to
 * highWatermark) binlog.
 */
public class MySqlBinlogSplitReadTask extends MySqlStreamingChangeEventSource {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlBinlogSplitReadTask.class);
    private final MySqlBinlogSplit binlogSplit;
    private final MySqlOffsetContext offsetContext;
    private final EventDispatcherImpl<TableId> eventDispatcher;
    private final SignalEventDispatcher signalEventDispatcher;
    private final ErrorHandler errorHandler;
    private ChangeEventSourceContext context;

    /**
     * Constructor of MySqlBinlogSplitReadTask.
     */
    public MySqlBinlogSplitReadTask(
            MySqlConnectorConfig connectorConfig,
            MySqlOffsetContext offsetContext,
            MySqlConnection connection,
            EventDispatcherImpl<TableId> dispatcher,
            ErrorHandler errorHandler,
            Clock clock,
            MySqlTaskContext taskContext,
            MySqlStreamingChangeEventSourceMetrics metrics,
            String topic,
            MySqlBinlogSplit binlogSplit) {
        super(
                connectorConfig,
                connection,
                dispatcher,
                errorHandler,
                clock,
                taskContext,
                metrics);
        this.binlogSplit = binlogSplit;
        this.eventDispatcher = dispatcher;
        this.offsetContext = offsetContext;
        this.errorHandler = errorHandler;
        this.signalEventDispatcher =
                new SignalEventDispatcher(
                        offsetContext.getPartition(), topic, eventDispatcher.getQueue());
    }

    @Override
    public void execute(ChangeEventSourceContext context, MySqlOffsetContext offsetContext)
        throws InterruptedException {
        this.context = context;
        super.execute(context, offsetContext);
    }

    @Override
    protected void handleEvent(MySqlOffsetContext offsetContext, Event event) {
        super.handleEvent(offsetContext, event);
        // check do we need to stop for read binlog for snapshot split.
        if (isBoundedRead()) {
            final BinlogOffset currentBinlogOffset = getBinlogPosition(offsetContext.getOffset());
            // reach the high watermark, the binlog reader should finished
            if (currentBinlogOffset.isAtOrAfter(binlogSplit.getEndingOffset())) {
                // send binlog end event
                try {
                    signalEventDispatcher.dispatchWatermarkEvent(
                            binlogSplit,
                            currentBinlogOffset,
                            SignalEventDispatcher.WatermarkKind.BINLOG_END);
                } catch (InterruptedException e) {
                    LOG.error("Send signal event error.", e);
                    errorHandler.setProducerThrowable(
                            new DebeziumException("Error processing binlog signal event", e));
                }
                // tell reader the binlog task finished
                ((SnapshotBinlogSplitChangeEventSourceContextImpl) context).finished();
            }
        }
    }

    private boolean isBoundedRead() {
        return !NO_STOPPING_OFFSET.equals(binlogSplit.getEndingOffset());
    }
}

package com.booking.replication.applier;

import static com.codahale.metrics.MetricRegistry.name;

import com.booking.replication.Configuration;
import com.booking.replication.Metrics;
import com.booking.replication.applier.kafka.KafkaMessageBufferException;
import com.booking.replication.applier.kafka.RowListMessage;
import com.booking.replication.augmenter.AugmentedRow;
import com.booking.replication.augmenter.AugmentedRowsEvent;
import com.booking.replication.augmenter.AugmentedSchemaChangeEvent;
import com.booking.replication.pipeline.PipelineOrchestrator;

import com.google.code.or.binlog.BinlogEventV4;
import com.google.code.or.binlog.impl.event.FormatDescriptionEvent;
import com.google.code.or.binlog.impl.event.QueryEvent;
import com.google.code.or.binlog.impl.event.RotateEvent;
import com.google.code.or.binlog.impl.event.TableMapEvent;
import com.google.code.or.binlog.impl.event.XidEvent;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Created by raynald on 08/06/16.
 */

public class KafkaApplier implements Applier {

    // how many rows go into one message
    private static final int MESSAGE_BATCH_SIZE = 10;

    private static boolean DRY_RUN;

    private static long totalRowsCounter = 0;
    private static long totalOutliersCounter = 0;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;

    private static List<String> fixedListOfIncludedTables;
    private static List<String> excludeTablePatterns;

    private static final HashMap<String,Boolean> wantedTables = new HashMap<String,Boolean>();

    // We need to make sure that all rows from one table end up on the same
    // partition. That is why we have a separate buffer for each parition, so
    // during buffering the right buffer is choosen.
    private HashMap<Integer,RowListMessage> partitionCurrentMessageBuffer = new HashMap<>();

    private String topicName;
    private AtomicBoolean exceptionFlag = new AtomicBoolean(false);

    private static final Meter kafka_messages = Metrics.registry.meter(name("Kafka", "producerToBroker"));
    private static final Counter exception_counter = Metrics.registry.counter(name("Kafka", "exceptionCounter"));
    private static final Counter outlier_counter = Metrics.registry.counter(name("Kafka", "outliersCounter"));
    private static final Timer closingTimer = Metrics.registry.timer(name("Kafka", "producerCloseTimer"));

    private static final HashMap<Integer, String> partitionLastBufferedRow = new HashMap<>();
    private static final HashMap<Integer, String> partitionLastCommittedMessage = new HashMap<>();

    private int numberOfPartition;
    private String brokerAddress;
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaApplier.class);
    private String rowLastPositionID = "";
    private String messageLastPositionID = "";

    private static Properties getProducerProperties(String broker) {
        // Below is the new version of producer configuration
        Properties prop = new Properties();
        prop.put("bootstrap.servers", broker);
        prop.put("acks", "all"); // Default 1
        prop.put("retries", 30); // Default value: 0
        prop.put("batch.size", 16384); // Default value: 16384
        prop.put("linger.ms", 20); // Default 0, Artificial delay
        prop.put("buffer.memory", 33554432); // Default value: 33554432
        prop.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prop.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prop.put("metric.reporters", "com.booking.replication.applier.KafkaMetricsCollector");
        prop.put("request.timeout.ms", 100000);
        return prop;
    }

    private static Properties getConsumerProperties(String broker) {
        // Consumer configuration
        Properties prop = new Properties();
        prop.put("bootstrap.servers", broker);
        prop.put("group.id", "getLastCommittedMessages");
        prop.put("auto.offset.reset", "latest");
        prop.put("enable.auto.commit", "false");
        prop.put("auto.commit.interval.ms", "1000");
        prop.put("session.timeout.ms", "30000");
        prop.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        prop.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        return prop;
    }

    public KafkaApplier(Configuration configuration) throws IOException {

        DRY_RUN = configuration.isDryRunMode();

        fixedListOfIncludedTables = configuration.getKafkaTableList();
        excludeTablePatterns = configuration.getKafkaExcludeTableList();
        topicName = configuration.getKafkaTopicName();
        brokerAddress = configuration.getKafkaBrokerAddress();

        if (!DRY_RUN) {
            producer = new KafkaProducer<>(getProducerProperties(brokerAddress));
            numberOfPartition = producer.partitionsFor(topicName).size();
            consumer = new KafkaConsumer<>(getConsumerProperties(brokerAddress));
            LOGGER.info("Start to fetch last positions");
            // Fetch last committed messages on each partition in order to prevent duplicate messages
            loadLastMessagePositionForEachPartition();
            LOGGER.info("Size of partitionLastCommittedMessage: " + partitionLastCommittedMessage.size());
            for (Integer i : partitionLastCommittedMessage.keySet()) {
                LOGGER.info("{ partition: " + i.toString()
                        + "} -> { lastCommittedMessageUniqueID: "
                        + partitionLastCommittedMessage.get(i)
                        + " }");
            }
        }
    }

    private void loadLastMessagePositionForEachPartition() throws IOException {
        // Method to fetch the last committed message in each partition of each topic.
        final int RetriesLimit = 100;
        final int POLL_TIME_OUT = 1000;
        ConsumerRecord<String, String> lastMessage;
        ConsumerRecords<String, String> messages;

        // loop partitions
        for (PartitionInfo pi: producer.partitionsFor(topicName)) {

            TopicPartition partition = new TopicPartition(topicName, pi.partition());
            consumer.assign(Collections.singletonList(partition));
            LOGGER.info("Position: " + String.valueOf(consumer.position(partition)));
            long endPosition = consumer.position(partition);

            // There is an edge case here. With a brand new partition, consumer position is equal to 0
            if (endPosition > 0) {
                LOGGER.info(String.format("Consumer seek to position minus one, current position %d", endPosition));
                consumer.seek(partition, endPosition - 1);
                if (consumer.position(partition) != endPosition - 1) {
                    LOGGER.error("Error seek position minus one");
                }
                int retries = 0;
                while (!partitionLastCommittedMessage.containsKey(pi.partition()) && retries < RetriesLimit) {
                    // We have rewinded the position one element back from the last one, so the list of messages
                    // returned by poll method will only contain one message
                    messages = consumer.poll(POLL_TIME_OUT);
                    if (!messages.isEmpty()) {

                        lastMessage = messages.iterator().next();

                        // ------------------------------------------------------------------------------
                        // Update last message position cache:
                        // if this message ID is not cached in the last committed message cache, or if
                        // there is a cached message ID that is older than the last message, update cache
                        // with the last message ID for this parition
                        String lastMessageBinlogPositionID = lastMessage.key();
                        if (!partitionLastCommittedMessage.containsKey(pi.partition())
                                || partitionLastCommittedMessage.get(pi.partition()).compareTo(lastMessageBinlogPositionID) < 0) {
                            partitionLastCommittedMessage.put(pi.partition(), lastMessageBinlogPositionID);
                        }

                        // ------------------------------------------------------------------------------
                        // Update row position cache:
                        //
                        // now we need to get the last row id that was in that last message and update last
                        // row position cache (that is needed to compare with rows arrving from producer)
                        // in order to avoid duplicate rows being pushed to kafka
                        String lastMessageJSON = lastMessage.value();
                        RowListMessage lastMessageDecoded = RowListMessage.fromJSON(lastMessageJSON);
                        String lastRowBinlogPositionID = lastMessageDecoded.getLastRowBinlogPositionID();
                        if (!partitionLastBufferedRow.containsKey(pi.partition())
                                || partitionLastBufferedRow.get(pi.partition()).compareTo(lastRowBinlogPositionID) < 0) {
                            partitionLastBufferedRow.put(pi.partition(), lastRowBinlogPositionID);
                        }
                    }
                    retries++;
                }
                if (!partitionLastCommittedMessage.containsKey(pi.partition())) {
                    LOGGER.error("Poll failed, probably the messages got purged!");
                    throw new RuntimeException("Poll failed, probably the messages got purged!");
                }
            }
        }
    }

    private boolean tableIsWanted(String tableName) {

        if (wantedTables.containsKey(tableName)) {
            return wantedTables.get(tableName);
        } else {
            // First check if the exclude pattern is specified. If
            // there is no exclude pattern, then check for the fixed
            // list of tables. If the exclude pattern is present it
            // overrides the fixed list of tables.
            if (excludeTablePatterns != null) {
                for (String excludePattern : excludeTablePatterns) {
                    Pattern compiledExcludePattern = Pattern.compile(excludePattern, Pattern.CASE_INSENSITIVE);
                    Matcher matcher = compiledExcludePattern.matcher(tableName);
                    if (matcher.find()) {
                        wantedTables.put(tableName,false);
                        return false;
                    }
                }
                // still here, meaning table should not be excluded
                wantedTables.put(tableName,true);
                return true;
            } else {
                // using fixed list of tables since the exclude pattern is
                // not specified
                for (String includedTable : fixedListOfIncludedTables) {
                    Pattern compiledIncludePattern = Pattern.compile(includedTable, Pattern.CASE_INSENSITIVE);
                    Matcher matcher = compiledIncludePattern.matcher(tableName);
                    if (matcher.find()) {
                        wantedTables.put(tableName,true);
                        return true;
                    }
                }
                // table is not in the included list, so should not be replicated
                wantedTables.put(tableName,false);
                return false;
            }
        }
    }

    @Override
    public void applyAugmentedRowsEvent(AugmentedRowsEvent augmentedRowsEvent, PipelineOrchestrator caller) {

        int partitionNum;
        String rowBinlogPositionID;

        for (AugmentedRow row : augmentedRowsEvent.getSingleRowEvents()) {

            if (exceptionFlag.get()) {
                throw new RuntimeException("Producer has problem with sending messages, could be a connection issue");
            }
            if (row.getTableName() == null) {
                LOGGER.error("tableName not exists");
                throw new RuntimeException("tableName does not exist");
            }

            String table = row.getTableName();

            if (tableIsWanted(table)) {

                totalRowsCounter++;

                // Row binlog position id
                rowBinlogPositionID = row.getRowBinlogPositionID();
                if (rowBinlogPositionID.compareTo(rowLastPositionID) <= 0) {
                    throw new RuntimeException("Something wrong with the row position. This should never happen.");
                }
                rowLastPositionID = rowBinlogPositionID;

                if (!DRY_RUN) {
                    partitionNum = (row.getTableName().hashCode() % numberOfPartition + numberOfPartition) % numberOfPartition;
                } else {
                    partitionNum = 0;
                }
                // Push to Kafka broker one of the following is true:
                //     1. there are no rows on current partition
                //     2. If current message unique ID is greater than the last committed message unique ID
                // TODO: move to isAfterLastRow() method
                if (!partitionLastBufferedRow.containsKey(partitionNum)
                        || rowBinlogPositionID.compareTo(partitionLastBufferedRow.get(partitionNum)) > 0) {

                    // if buffer is not initialized for partition, do init
                    if (partitionCurrentMessageBuffer.get(partitionNum) == null) {
                        List<AugmentedRow> rowsBucket = new ArrayList();
                        rowsBucket.add(row);
                        partitionCurrentMessageBuffer.put(partitionNum, new RowListMessage(MESSAGE_BATCH_SIZE, rowsBucket));
                    } else {
                        // if buffer is full do:
                        //      (close) -> (send message) -> (create new buffer - sets current row as the first in the buffer)
                        // else:
                        //      (add current row to the buffer)
                        if (partitionCurrentMessageBuffer.get(partitionNum).isFull()) {

                            // 1. close buffer
                            partitionCurrentMessageBuffer.get(partitionNum).closeMessageBuffer();

                            // 2. send message
                            sendMessage(partitionNum);

                            // 3. open new buffer with current row as buffer-start-row
                            List<AugmentedRow> rowsBucket = new ArrayList();
                            rowsBucket.add(row);
                            partitionCurrentMessageBuffer.put(partitionNum, new RowListMessage(MESSAGE_BATCH_SIZE, rowsBucket));

                        } else {
                            // buffer row to current buffer
                            try {
                                partitionCurrentMessageBuffer.get(partitionNum).addRowToMessage(row);
                            } catch (KafkaMessageBufferException ke) {
                                LOGGER.error("Trying to write to a closed buffer. This should never happen. Exiting...");
                                System.exit(-1);
                            }
                        }
                    }
                    kafka_messages.mark();
                }
            } else {
                totalOutliersCounter ++;
                outlier_counter.inc();
            }
        } // next row
    }

    private void sendMessage(int partitionNum) {

        RowListMessage rowListMessage = partitionCurrentMessageBuffer.get(partitionNum);

        String jsonMessage = rowListMessage.toJSON();

        if (!DRY_RUN) {
            ProducerRecord<String, String> message;

            message = new ProducerRecord<>(
                    topicName,
                    partitionNum,
                    rowListMessage.getMessageBinlogPositionID(),
                    jsonMessage);

            producer.send(message, new Callback() {
                @Override
                public void onCompletion(RecordMetadata recordMetadata, Exception sendException) {
                    if (sendException != null) {
                        LOGGER.error("Error producing to Kafka broker", sendException);
                        exceptionFlag.set(true);
                        exception_counter.inc();
                    }
                }
            });
        } else {
            System.out.println(jsonMessage);
        }
    }

    @Override
    public void applyCommitQueryEvent(QueryEvent event) {

    }

    @Override
    public void applyXidEvent(XidEvent event) {

    }

    @Override
    public void applyRotateEvent(RotateEvent event) {

    }

    @Override
    public void applyAugmentedSchemaChangeEvent(AugmentedSchemaChangeEvent augmentedSchemaChangeEvent, PipelineOrchestrator caller) {

    }

    @Override
    public void forceFlush() {
        final Timer.Context context = closingTimer.time();
        // Producer close does the waiting, see documentation.
        producer.close();
        context.stop();
        producer = new KafkaProducer<>(getProducerProperties(brokerAddress));
        LOGGER.info("A new producer has been created");
    }

    @Override
    public void applyFormatDescriptionEvent(FormatDescriptionEvent event) {

    }

    @Override
    public void applyTableMapEvent(TableMapEvent event) {

    }

    @Override
    public void waitUntilAllRowsAreCommitted(BinlogEventV4 event) {
        final Timer.Context context = closingTimer.time();
        // Producer close does the waiting, see documentation.
        producer.close();
        context.stop();
        producer = new KafkaProducer<>(getProducerProperties(brokerAddress));
        LOGGER.info("A new producer has been created");
    }
}

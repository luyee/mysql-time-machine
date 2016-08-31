package com.booking.replication.checkpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Created by bosko on 5/30/16.
 */
public class LastCommitedPositionCheckpoint implements SafeCheckPoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(LastCommitedPositionCheckpoint.class);

    private final int checkpointType = SafeCheckpointType.BINLOG_POSITION;

    private int    slaveId;
    private String lastVerifiedBinlogFileName;
    private long   lastVerifiedBinlogPosition = 4L;

    // this is only for tracking purposes. The HA pGTID safe checkpoint will
    // be implemented in different class
    private String pseudoGTID;

    public LastCommitedPositionCheckpoint() {}

    public LastCommitedPositionCheckpoint(int slaveId, String binlogFileName) {
        this(slaveId, binlogFileName, 4L);
    }

    /**
     * Represents the last processed binlog file with last commited position.
     *
     * @param slaveId           Id of the slave that originated the binlog.
     * @param binlogFileName    File name
     * @param binlogPosition    File position
     */
    public LastCommitedPositionCheckpoint(int slaveId, String binlogFileName, long binlogPosition) {
        this.slaveId = slaveId;
        lastVerifiedBinlogFileName = binlogFileName;
        lastVerifiedBinlogPosition = binlogPosition;
    }

    /**
     * Represents the last processed binlog file with last commited postion and pGTID.
     *
     * @param slaveId           Id of the slave that originated the binlog.
     * @param binlogFileName    File name
     * @param binlogPosition    File position
     * @param pseudoGTID        Pseudo GTID
     */
    public LastCommitedPositionCheckpoint(int slaveId, String binlogFileName, long binlogPosition, String pseudoGTID) {
        this.slaveId = slaveId;
        lastVerifiedBinlogFileName = binlogFileName;
        lastVerifiedBinlogPosition = binlogPosition;
        this.pseudoGTID            = pseudoGTID;
    }

    @Override
    public int getCheckpointType() {
        return this.checkpointType;
    }

    public Long getLastVerifiedBinlogPosition() {
        return  lastVerifiedBinlogPosition;
    }

    public int getSlaveId() {
        return slaveId;
    }

    public String getLastVerifiedBinlogFileName() {
        return lastVerifiedBinlogFileName;
    }

    public void setSlaveId(int slaveId) {
        this.slaveId = slaveId;
    }

    public void setLastVerifiedBinlogFileName(String lastVerifiedBinlogFileName) {
        this.lastVerifiedBinlogFileName = lastVerifiedBinlogFileName;
    }

    public void setLastVerifiedBinlogPosition(long lastVerifiedBinlogPosition) {
        this.lastVerifiedBinlogPosition = lastVerifiedBinlogPosition;
    }

    public String getPseudoGTID() {
        return pseudoGTID;
    }

    public void setPseudoGTID(String pseudoGTID) {
        this.pseudoGTID = pseudoGTID;
    }

    @Override
    public String getSafeCheckPointMarker() {
        return lastVerifiedBinlogFileName;
    }

    @Override
    public void setSafeCheckPointMarker(String marker) {
        lastVerifiedBinlogFileName = marker;
        LOGGER.info("SafeCheckPoint marker set to: " + lastVerifiedBinlogFileName);
    }

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toJson() {
        String json = null;
        try {
            json = mapper.writeValueAsString(this);
        } catch (IOException e) {
            LOGGER.error("ERROR: could not serialize event", e);
        }
        return json;
    }
}

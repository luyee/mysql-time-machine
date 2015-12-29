package com.booking.replication.util;

/**
 * Simple utility class for parsing command line options
 */
import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class CMD {

    public static OptionSet parseArgs(String[] args) {

        OptionParser parser = new OptionParser();

        parser.accepts("dc").withRequiredArg().ofType(String.class);
        parser.accepts("schema").withRequiredArg().ofType(String.class);
        parser.accepts("applier").withRequiredArg().ofType(String.class);
        parser.accepts("binlog-filename").withRequiredArg().ofType(String.class);
        parser.accepts("position").withRequiredArg().ofType(Long.class);
        parser.accepts("config-path").withRequiredArg().ofType(String.class);
        parser.accepts("shard").withRequiredArg().ofType(Integer.class);

        OptionSet options = parser.parse(args);

        return options;
    }
}


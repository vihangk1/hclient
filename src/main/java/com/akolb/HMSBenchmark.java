package com.akolb;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;

import java.io.IOException;
import java.net.Socket;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;

import static com.akolb.HMSClient.makeTable;
import static com.akolb.Main.DEFAULT_PORT;
import static com.akolb.Main.OPT_DATABASE;
import static com.akolb.Main.OPT_DROP;
import static com.akolb.Main.OPT_NUMBER;
import static com.akolb.Main.OPT_PARTITIONS;
import static com.akolb.Main.OPT_PATTERN;
import static com.akolb.Main.OPT_SERVER;
import static com.akolb.Main.OPT_TABLE;
import static com.akolb.Main.OPT_VERBOSE;
import static com.akolb.Main.getServerUri;
import static com.akolb.Main.help;

public class HMSBenchmark {
  private static Logger LOG = Logger.getLogger(HMSBenchmark.class.getName());
  private static long scale = ChronoUnit.MILLIS.getDuration().getNano();

  public static void main(String[] args) throws Exception {
    Options options = new Options();
    options.addOption("s", OPT_SERVER, true, "HMS Server")
        .addOption("P", OPT_PARTITIONS, true, "partitions list")
        .addOption("h", "help", false, "print this info")
        .addOption("d", OPT_DATABASE, true, "database name (can be regexp for list)")
        .addOption("t", OPT_TABLE, true, "table name (can be regexp for list)")
        .addOption("v", OPT_VERBOSE, false, "verbose mode")
        .addOption("N", OPT_NUMBER, true, "number of instances")
        .addOption("S", OPT_PATTERN, true, "table name pattern for bulk creation")
        .addOption("D", OPT_DROP, false, "drop table if exists");

    CommandLineParser parser = new DefaultParser();

    CommandLine cmd = null;

    try {
      cmd = parser.parse(options, args);
    } catch (ParseException e) {
      help(options);
      System.exit(1);
    }

    if (cmd.hasOption("help")) {
      help(options);
    }

    String server = getServerUri(cmd).toString();

    LOG.info("connecting to " + server);

    String dbName = cmd.getOptionValue(OPT_DATABASE);
    String tableName = cmd.getOptionValue(OPT_TABLE);

    if (tableName != null && tableName.contains(".")) {
      String[] parts = tableName.split("\\.");
      dbName = parts[0];
      tableName = parts[1];
    }

    if (dbName == null || dbName.isEmpty()) {
      throw new RuntimeException("Missing DB name");
    }
    if (tableName == null || tableName.isEmpty()) {
      throw new RuntimeException("Missing Table name");
    }

    LOG.info("Using table '" + dbName + "." + tableName + "'");

    try (HMSClient client = new HMSClient(server)) {

      if (!client.dbExists(dbName)) {
        client.createDatabase(dbName);
      }

      if (client.tableExists(dbName, tableName)) {
        client.dropTable(dbName, tableName);
      }

      LOG.info("Measure network latency");
      DescriptiveStatistics netStats = benchmarkNetworkLatency(getServerUri(cmd).getHost(),
          DEFAULT_PORT);
      double latency = netStats.getMean();
      benchmarkTableCreate(client, dbName, tableName, latency);
      benchmarkListDatabases(client, latency);
      benchmarkListTables(client, dbName, latency);
      benchmarkListTables100(client, dbName, latency);
    }
  }

  private static void benchmarkTableCreate(final HMSClient client,
                                           final String dbName, final String tableName,
                                           double latency) {
    Table table = makeTable(dbName, tableName, null, null);

    MicroBenchmark bench = new MicroBenchmark();
    LOG.info("Measuring create table times");

    DescriptiveStatistics stats = bench.measure(null,
        () -> client.createTableNoException(table),
        () -> client.dropTableNoException(dbName, tableName));

    displayStats(stats, "createTable()", latency);

    LOG.info("Measuring delete table times");
    stats = bench.measure(
        () -> client.createTableNoException(table),
        () ->client.dropTableNoException(dbName, tableName),
        null);
    displayStats(stats, "dropTable()", latency);
  }

  private static DescriptiveStatistics benchmarkNetworkLatency(final String server, int port) {
    MicroBenchmark bench = new MicroBenchmark(10, 50);
    LOG.info("Measuring socket connection times");

    DescriptiveStatistics stats = bench.measure(
        () -> {
          try (Socket socket = new Socket(server, port)) {
          } catch (IOException e) {
            e.printStackTrace();
          }
        });
    displayStats(stats, "Connect()", 0);
    return stats;
  }

  private static void benchmarkListDatabases(final HMSClient client, double latency) {
    MicroBenchmark bench = new MicroBenchmark();
    LOG.info("Measuring list databases");
    DescriptiveStatistics stats = bench.measure(client::getAllDatabasesNoException);
    displayStats(stats, "getAllDatabases", latency);
  }

  private static void benchmarkListTables(final HMSClient client, final String dbName, double latency) {
    MicroBenchmark bench = new MicroBenchmark();
    LOG.info("Measuring list tables");
    DescriptiveStatistics stats = bench.measure(() -> client.getAllTablesNoException(dbName));
    displayStats(stats, "getAllTables", latency);
  }

  private static void benchmarkListTables100(HMSClient client, String dbName, double latency) {
    // Create a bunch of tables
    try {
      for (int i = 0; i < 100; i++) {
        String tName = "tmp_table_" + i;
        Table table = makeTable(dbName, tName, null, null);
        client.createTable(table);
      }
      MicroBenchmark bench = new MicroBenchmark();
      LOG.info("Measuring list tables with 100 tables");
      DescriptiveStatistics stats = bench.measure(() -> client.getAllTablesNoException(dbName));
      displayStats(stats, "getAllTables(multiple)", latency);
    } catch (TException e) {
      e.printStackTrace();
      return;
    } finally {
      // Cleanup - delete temp tables
      for (int i = 0; i < 100; i++) {
        String tName = "tmp_table_" + i;
        try {
          client.dropTable(dbName, tName);
        } catch (TException e) {
          e.printStackTrace();
        }
      }
    }
  }

  private static void displayStats(DescriptiveStatistics stats, String name, double latency) {
    double err = stats.getStandardDeviation() / stats.getMean() * 100;
    System.out.printf("%s: Mean: %g ms, Adjusted: %g ms, [%g, %g], +/- %g%% %n", name,
        stats.getMean() / scale,
        (stats.getMean() - latency) / scale,
        stats.getMin() / scale,
        stats.getMax() / scale,
        err);
  }
}

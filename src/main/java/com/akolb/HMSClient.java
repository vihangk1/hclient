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

package com.akolb;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.DropPartitionsRequest;
import org.apache.hadoop.hive.metastore.api.DropPartitionsResult;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.RequestPartsSpec;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.hive.thrift.HadoopThriftAuthBridge;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFastFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class HMSClient implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(HMSClient.class);
  private static final String METASTORE_URI = "hive.metastore.uris";
  private static final String CONFIG_DIR = "/etc/hive/conf";
  private static final String HIVE_SITE = "hive-site.xml";
  private static final String CORE_SITE = "core-site.xml";
  private static final String PRINCIPAL_KEY = "hive.metastore.kerberos.principal";
  private static final long SOCKET_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(600);

  private final String confDir;
  private ThriftHiveMetastore.Iface client;
  private TTransport transport;
  private URI serverURI;

  public URI getServerURI() {
    return serverURI;
  }

  /**
   * Version if the SUpplier that can throw exceptions
   * @param <T>
   * @param <E>
   */
  @FunctionalInterface
  public interface ThrowingSupplier<T, E extends Exception> {
    T get() throws E;
  }

  /**
   * Wrapper that moves all checked exceptions to RuntimeException
   * @param throwingSupplier Supplier that throws Exception
   * @param <T> Supplier return type
   * @return Supplier that throws unchecked exception
   */
  public static <T> T throwingSupplierWrapper(ThrowingSupplier<T, Exception> throwingSupplier) {
    try {
      return throwingSupplier.get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String toString() {
    return serverURI.toString();
  }

  HMSClient(@Nullable URI uri)
      throws TException, IOException, InterruptedException, LoginException, URISyntaxException {
    this(uri, CONFIG_DIR);
  }

  HMSClient(@Nullable URI uri, @Nullable String confDir)
      throws TException, IOException, InterruptedException, LoginException, URISyntaxException {
    this.confDir = (confDir == null ? CONFIG_DIR : confDir);
    getClient(uri);
  }

  private void addResource(Configuration conf, @NotNull String r) throws MalformedURLException {
    File f = new File(confDir + "/" + r);
    if (f.exists() && !f.isDirectory()) {
      LOG.debug("Adding configuration resource {}", r);
      conf.addResource(f.toURI().toURL());
    } else {
      LOG.debug("Configuration {} does not exist", r);
    }
  }

  /**
   * Create a client to Hive Metastore.
   * If principal is specified, create kerberised client.
   *
   * @param uri server uri
   * @throws MetaException        if fails to login using kerberos credentials
   * @throws IOException          if fails connecting to metastore
   * @throws InterruptedException if interrupted during kerberos setup
   */
  private void getClient(@Nullable URI uri)
      throws TException, IOException, InterruptedException, URISyntaxException, LoginException {
    HiveConf conf = new HiveConf();
    addResource(conf, HIVE_SITE);
    if (uri != null) {
      conf.set(METASTORE_URI, uri.toString());
    }

    // Pick up the first URI from the list of available URIs
    serverURI = uri != null ?
        uri :
        new URI(conf.get(METASTORE_URI).split(",")[0]);

    String principal = conf.get(PRINCIPAL_KEY);

    if (principal == null) {
      open(conf, serverURI);
      return;
    }

    LOG.debug("Opening kerberos connection to HMS");
    addResource(conf, CORE_SITE);

    Configuration hadoopConf = new Configuration();
    addResource(hadoopConf, HIVE_SITE);
    addResource(hadoopConf, CORE_SITE);

    // Kerberos magic
    UserGroupInformation.setConfiguration(hadoopConf);
    UserGroupInformation.getLoginUser()
        .doAs((PrivilegedExceptionAction<TTransport>)
            () -> open(conf, serverURI));
  }

  boolean dbExists(@NotNull String dbName) throws TException {
    return getAllDatabases(dbName).contains(dbName);
  }

  boolean tableExists(@NotNull String dbName, @NotNull String tableName) throws TException {
    return getAllTables(dbName, tableName).contains(tableName);
  }

  Database getDatabase(@NotNull String dbName) throws TException {
    return client.get_database(dbName);
  }

  /**
   * Return all databases with name matching the filter.
   *
   * @param filter Regexp. Can be null or empty in which case everything matches
   * @return list of database names matching the filter
   * @throws MetaException
   */
  Set<String> getAllDatabases(@Nullable String filter) throws TException {
    if (filter == null || filter.isEmpty()) {
      return new HashSet<>(client.get_all_databases());
    }
    return client.get_all_databases()
        .stream()
        .filter(n -> n.matches(filter))
        .collect(Collectors.toSet());
  }

  Set<String> getAllTables(@NotNull String dbName, @Nullable String filter) throws TException {
    if (filter == null || filter.isEmpty()) {
      return new HashSet<>(client.get_all_tables(dbName));
    }
    return client.get_all_tables(dbName)
        .stream()
        .filter(n -> n.matches(filter))
        .collect(Collectors.toSet());
  }

  /**
   * Create database with the given name if it doesn't exist
   *
   * @param name database name
   */
  boolean createDatabase(@NotNull String name) throws TException {
    return createDatabase(name, null, null, null);
  }

  /**
   * Create database if it doesn't exist
   * @param name Database name
   * @param description Database description
   * @param location Database location
   * @param params Database params
   * @throws TException if database exists
   */
  boolean createDatabase(@NotNull String name,
                      @Nullable String description,
                      @Nullable String location,
                      @Nullable Map<String, String> params)
      throws TException {
    Database db = new Database(name, description, location, params);
    client.create_database(db);
    return true;
  }

  boolean createDatabase(Database db) throws TException {
    client.create_database(db);
    return true;
  }

  boolean dropDatabase(@NotNull String dbName) throws TException {
    client.drop_database(dbName, true, true);
    return true;
  }

  boolean createTable(Table table) throws TException {
    client.create_table(table);
    return true;
  }

  boolean dropTable(@NotNull String dbName, @NotNull String tableName) throws TException {
    client.drop_table(dbName, tableName, true);
    return true;
  }

  Table getTable(@NotNull String dbName, @NotNull String tableName) throws TException {
    return client.get_table(dbName, tableName);
  }

  Partition createPartition(@NotNull Table table, @NotNull List<String> values) throws TException {
    return client.add_partition(new Util.PartitionBuilder(table).setValues(values).build());
  }

  Partition addPartition(@NotNull Partition partition) throws TException {
    return client.add_partition(partition);
  }

  void createPartitions(List<Partition> partitions) throws TException {
    client.add_partitions(partitions);
  }


  List<Partition> listPartitions(@NotNull String dbName,
                                 @NotNull String tableName) throws TException {
    return client.get_partitions(dbName, tableName, (short) -1);
  }

  Long getCurrentNotificationId() throws TException {
    return client.get_current_notificationEventId().getEventId();
  }

  List<String> getPartitionNames(@NotNull String dbName,
                                 @NotNull String tableName) throws TException {
    return client.get_partition_names(dbName, tableName, (short) -1);
  }

  public boolean dropPartition(@NotNull String dbName, @NotNull String tableName,
                               @NotNull List<String> arguments)
      throws TException {
    return client.drop_partition(dbName, tableName, arguments, true);
  }

  List<Partition> getPartitions(@NotNull String dbName, @NotNull String tableName) throws TException {
    return client.get_partitions(dbName, tableName, (short)-1);
  }

  DropPartitionsResult dropPartitions(@NotNull String dbName, @NotNull String tableName,
                                      @Nullable List<String> partNames) throws TException {
    if (partNames == null) {
      return dropPartitions(dbName, tableName, getPartitionNames(dbName, tableName));
    }
    if (partNames.isEmpty()) {
      return null;
    }
    return client.drop_partitions_req(new DropPartitionsRequest(dbName,
        tableName, RequestPartsSpec.names(partNames)));
  }

  List<Partition> getPartitionsByNames(@NotNull String dbName, @NotNull String tableName,
                                       @Nullable List<String>names) throws TException {
    if (names == null) {
      return client.get_partitions_by_names(dbName, tableName,
          getPartitionNames(dbName, tableName));
    }
    return client.get_partitions_by_names(dbName, tableName, names);
  }

  boolean alterTable(@NotNull String dbName, @NotNull String tableName, @NotNull Table newTable)
      throws TException {
    client.alter_table(dbName, tableName, newTable);
    return true;
  }

  void alterPartition(@NotNull String dbName, @NotNull String tableName,
      @NotNull Partition partition) throws TException {
    client.alter_partition(dbName, tableName, partition);
  }

  void alterPartitions(@NotNull String dbName, @NotNull String tableName,
      @NotNull List<Partition> partitions) throws TException {
    client.alter_partitions(dbName, tableName, partitions);
  }

  void appendPartition(@NotNull String dbName, @NotNull String tableName,
      @NotNull List<String> partitionValues) throws TException {
    client.append_partition_with_environment_context(dbName, tableName, partitionValues, null);
  }

  private TTransport open(HiveConf conf, @NotNull URI uri) throws
      TException, IOException, LoginException {
    boolean useSasl = conf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL);
    boolean useFramedTransport = conf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_FRAMED_TRANSPORT);
    boolean useCompactProtocol = conf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_COMPACT_PROTOCOL);
    LOG.debug("Connecting to {}, framedTransport = {}", uri, useFramedTransport);

    transport = new TSocket(uri.getHost(), uri.getPort(), (int) SOCKET_TIMEOUT_MS);

    if (useSasl) {
      LOG.debug("Using SASL authentication");
      HadoopThriftAuthBridge.Client authBridge =
          ShimLoader.getHadoopThriftAuthBridge().createClient();
      // check if we should use delegation tokens to authenticate
      // the call below gets hold of the tokens if they are set up by hadoop
      // this should happen on the map/reduce tasks if the client added the
      // tokens into hadoop's credential store in the front end during job
      // submission.
      String tokenSig = conf.get("hive.metastore.token.signature");
      // tokenSig could be null
      String tokenStrForm = Utils.getTokenStrForm(tokenSig);
      if (tokenStrForm != null) {
        LOG.debug("Using delegation tokens");
        // authenticate using delegation tokens via the "DIGEST" mechanism
        transport = authBridge.createClientTransport(null, uri.getHost(),
            "DIGEST", tokenStrForm, transport,
            MetaStoreUtils.getMetaStoreSaslProperties(conf));
      } else {
        LOG.debug("Using principal");
        String principalConfig =
            conf.getVar(HiveConf.ConfVars.METASTORE_KERBEROS_PRINCIPAL);
        LOG.debug("Using principal {}", principalConfig);
        transport = authBridge.createClientTransport(
            principalConfig, uri.getHost(), "KERBEROS", null,
            transport, MetaStoreUtils.getMetaStoreSaslProperties(conf));
      }
    }

    transport = useFramedTransport ? new TFastFramedTransport(transport) : transport;
    TProtocol protocol = useCompactProtocol ?
        new TCompactProtocol(transport) :
        new TBinaryProtocol(transport);
    client = new ThriftHiveMetastore.Client(protocol);
    transport.open();
    if (!useSasl && conf.getBoolVar(HiveConf.ConfVars.METASTORE_EXECUTE_SET_UGI)) {
      UserGroupInformation ugi = Utils.getUGI();
      client.set_ugi(ugi.getUserName(), Arrays.asList(ugi.getGroupNames()));
      LOG.debug("Set UGI for {}", ugi.getUserName());
    }
    LOG.debug("Connected to metastore, using compact protocol = {}", useCompactProtocol);
    return transport;
  }

  @Override
  public void close() throws Exception {
    if ((transport != null) && transport.isOpen()) {
      LOG.debug("Closing thrift transport");
      transport.close();
    }
  }
}

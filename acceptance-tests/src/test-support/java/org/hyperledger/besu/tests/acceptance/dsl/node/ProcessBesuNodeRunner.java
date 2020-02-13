/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.hyperledger.besu.tests.acceptance.dsl.node;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.hyperledger.besu.cli.options.NetworkingOptions;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcApi;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcApis;
import org.hyperledger.besu.ethereum.permissioning.PermissioningConfiguration;
import org.hyperledger.besu.metrics.prometheus.MetricsConfiguration;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;
import org.hyperledger.besu.tests.acceptance.dsl.StaticNodesUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

public class ProcessBesuNodeRunner implements BesuNodeRunner {

  private final Logger LOG = LogManager.getLogger();
  private final Logger PROCESS_LOG = LogManager.getLogger("org.hyperledger.besu.SubProcessLog");

  private final Map<String, Process> besuProcesses = new HashMap<>();
  private final ExecutorService outputProcessorExecutor = Executors.newCachedThreadPool();

  ProcessBesuNodeRunner() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
  }

  @Override
  public void startNode(final BesuNode node) {

    if (ThreadContext.containsKey("node")) {
      LOG.error("ThreadContext node is already set to {}", ThreadContext.get("node"));
    }
    ThreadContext.put("node", node.getName());

    final Path dataDir = node.homeDirectory();

    final List<String> params = new ArrayList<>();
    params.add("build/install/besu/bin/besu");

    params.add("--data-path");
    params.add(dataDir.toAbsolutePath().toString());

    if (node.isDevMode()) {
      params.add("--network");
      params.add("DEV");
    }

    params.add("--discovery-enabled");
    params.add(Boolean.toString(node.isDiscoveryEnabled()));

    params.add("--p2p-host");
    params.add(node.p2pListenHost());

    params.add("--p2p-port");
    params.add("0");

    if (node.getMiningParameters().isMiningEnabled()) {
      params.add("--miner-enabled");
      params.add("--miner-coinbase");
      params.add(node.getMiningParameters().getCoinbase().get().toString());
      params.add("--min-gas-price");
      params.add(node.getMiningParameters().getMinTransactionGasPrice().toString());
    }

    if (node.getPrivacyParameters().isEnabled()) {
      params.add("--privacy-enabled");
      params.add("--privacy-url");
      params.add(node.getPrivacyParameters().getEnclaveUri().toString());
      params.add("--privacy-public-key-file");
      params.add(node.getPrivacyParameters().getEnclavePublicKeyFile().getAbsolutePath());
      params.add("--privacy-precompiled-address");
      params.add(String.valueOf(node.getPrivacyParameters().getPrivacyAddress()));
      params.add("--privacy-marker-transaction-signing-key-file");
      params.add(node.homeDirectory().resolve("key").toString());
    }

    params.add("--bootnodes");

    if (!node.getBootnodes().isEmpty()) {
      params.add(node.getBootnodes().stream().map(URI::toString).collect(Collectors.joining(",")));
    }

    if (node.hasStaticNodes()) {
      createStaticNodes(node);
    }

    if (node.isJsonRpcEnabled()) {
      params.add("--rpc-http-enabled");
      params.add("--rpc-http-host");
      params.add(node.jsonRpcListenHost().get());
      params.add("--rpc-http-port");
      params.add(node.jsonRpcListenPort().map(Object::toString).get());
      params.add("--rpc-http-api");
      params.add(apiList(node.jsonRpcConfiguration().getRpcApis()));
      if (node.jsonRpcConfiguration().isAuthenticationEnabled()) {
        params.add("--rpc-http-authentication-enabled");
      }
      if (node.jsonRpcConfiguration().getAuthenticationCredentialsFile() != null) {
        params.add("--rpc-http-authentication-credentials-file");
        params.add(node.jsonRpcConfiguration().getAuthenticationCredentialsFile());
      }
    }

    if (node.wsRpcEnabled()) {
      params.add("--rpc-ws-enabled");
      params.add("--rpc-ws-host");
      params.add(node.wsRpcListenHost().get());
      params.add("--rpc-ws-port");
      params.add(node.wsRpcListenPort().map(Object::toString).get());
      params.add("--rpc-ws-api");
      params.add(apiList(node.webSocketConfiguration().getRpcApis()));
      if (node.webSocketConfiguration().isAuthenticationEnabled()) {
        params.add("--rpc-ws-authentication-enabled");
      }
      if (node.webSocketConfiguration().getAuthenticationCredentialsFile() != null) {
        params.add("--rpc-ws-authentication-credentials-file");
        params.add(node.webSocketConfiguration().getAuthenticationCredentialsFile());
      }
    }

    if (node.isMetricsEnabled()) {
      final MetricsConfiguration metricsConfiguration = node.getMetricsConfiguration();
      params.add("--metrics-enabled");
      params.add("--metrics-host");
      params.add(metricsConfiguration.getHost());
      params.add("--metrics-port");
      params.add(Integer.toString(metricsConfiguration.getPort()));
      for (final MetricCategory category : metricsConfiguration.getMetricCategories()) {
        params.add("--metrics-category");
        params.add(((Enum<?>) category).name());
      }
      if (metricsConfiguration.isPushEnabled()) {
        params.add("--metrics-push-enabled");
        params.add("--metrics-push-host");
        params.add(metricsConfiguration.getPushHost());
        params.add("--metrics-push-port");
        params.add(Integer.toString(metricsConfiguration.getPushPort()));
        params.add("--metrics-push-interval");
        params.add(Integer.toString(metricsConfiguration.getPushInterval()));
        params.add("--metrics-push-prometheus-job");
        params.add(metricsConfiguration.getPrometheusJob());
      }
    }

    node.getGenesisConfig()
        .ifPresent(
            genesis -> {
              final Path genesisFile = createGenesisFile(node, genesis);
              params.add("--genesis-file");
              params.add(genesisFile.toAbsolutePath().toString());
            });

    if (!node.isP2pEnabled()) {
      params.add("--p2p-enabled");
      params.add("false");
    } else {
      final List<String> networkConfigParams =
          NetworkingOptions.fromConfig(node.getNetworkingConfiguration()).getCLIOptions();
      params.addAll(networkConfigParams);
    }

    if (node.isRevertReasonEnabled()) {
      params.add("--revert-reason-enabled");
    }

    node.getPermissioningConfiguration()
        .flatMap(PermissioningConfiguration::getLocalConfig)
        .ifPresent(
            permissioningConfiguration -> {
              if (permissioningConfiguration.isNodeWhitelistEnabled()) {
                params.add("--permissions-nodes-config-file-enabled");
              }
              if (permissioningConfiguration.getNodePermissioningConfigFilePath() != null) {
                params.add("--permissions-nodes-config-file");
                params.add(permissioningConfiguration.getNodePermissioningConfigFilePath());
              }
              if (permissioningConfiguration.isAccountWhitelistEnabled()) {
                params.add("--permissions-accounts-config-file-enabled");
              }
              if (permissioningConfiguration.getAccountPermissioningConfigFilePath() != null) {
                params.add("--permissions-accounts-config-file");
                params.add(permissioningConfiguration.getAccountPermissioningConfigFilePath());
              }
            });

    node.getPermissioningConfiguration()
        .flatMap(PermissioningConfiguration::getSmartContractConfig)
        .ifPresent(
            permissioningConfiguration -> {
              if (permissioningConfiguration.isSmartContractNodeWhitelistEnabled()) {
                params.add("--permissions-nodes-contract-enabled");
              }
              if (permissioningConfiguration.getNodeSmartContractAddress() != null) {
                params.add("--permissions-nodes-contract-address");
                params.add(permissioningConfiguration.getNodeSmartContractAddress().toString());
              }
              if (permissioningConfiguration.isSmartContractAccountWhitelistEnabled()) {
                params.add("--permissions-accounts-contract-enabled");
              }
              if (permissioningConfiguration.getAccountSmartContractAddress() != null) {
                params.add("--permissions-accounts-contract-address");
                params.add(permissioningConfiguration.getAccountSmartContractAddress().toString());
              }
            });
    params.addAll(node.getExtraCLIOptions());

    params.add("--key-value-storage");
    params.add("rocksdb");

    // in testing, we don't mind logging on success, but we want max info on failures
    params.add("--logging=DEBUG");

    LOG.info("Creating besu process with params {}", params);
    final ProcessBuilder processBuilder =
        new ProcessBuilder(params)
            .directory(new File(System.getProperty("user.dir")).getParentFile())
            .redirectErrorStream(true)
            .redirectInput(Redirect.INHERIT);
    if (!node.getPlugins().isEmpty()) {
      processBuilder
          .environment()
          .put(
              "BESU_OPTS",
              "-Dbesu.plugins.dir=" + dataDir.resolve("plugins").toAbsolutePath().toString());
    }

    try {
      final Process process = processBuilder.start();
      outputProcessorExecutor.execute(() -> printOutput(node, process));
      besuProcesses.put(node.getName(), process);
    } catch (final IOException e) {
      LOG.error("Error starting BesuNode process", e);
    }

    waitForPortsFile(dataDir);

    ThreadContext.remove("node");
  }

  private void printOutput(final BesuNode node, final Process process) {
    try (final BufferedReader in =
        new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
      String line = in.readLine();
      while (line != null) {
        // would be nice to pass up the log level of the incoming log line
        PROCESS_LOG.info(line);
        line = in.readLine();
      }
    } catch (final IOException e) {
      if (besuProcesses.containsKey(node.getName())) {
        LOG.error("Failed to read output from process for node " + node.getName(), e);
        if (process.isAlive()) {
          LOG.error("Process is still alive, killing it now");
          stopNode(node);
        } else {
          LOG.error("Node exited with code {}", process.exitValue());
        }
      } else {
        LOG.debug("Stdout from process {} closed", node.getName());
        assert(!process.isAlive());
      }
    }
  }

  private Path createGenesisFile(final BesuNode node, final String genesisConfig) {
    try {
      final Path genesisFile = Files.createTempFile(node.homeDirectory(), "genesis", "");
      genesisFile.toFile().deleteOnExit();
      Files.write(genesisFile, genesisConfig.getBytes(UTF_8));
      return genesisFile;
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void createStaticNodes(final BesuNode node) {
    StaticNodesUtils.createStaticNodesFile(node.homeDirectory(), node.getStaticNodes());
  }

  private String apiList(final Collection<RpcApi> rpcApis) {
    return rpcApis.stream().map(RpcApis::getValue).collect(Collectors.joining(","));
  }

  @Override
  public void stopNode(final BesuNode node) {
    node.stop();
    if (besuProcesses.containsKey(node.getName())) {
      final Process process = besuProcesses.get(node.getName());
      killBesuProcess(node.getName(), process);
    } else {
      LOG.error("There was a request to stop a node not in the known list: {}", node.getName());
    }
  }

  @Override
  public synchronized void shutdown() {
    final HashMap<String, Process> localMap = new HashMap<>(besuProcesses);
    localMap.forEach(this::killBesuProcess);
    outputProcessorExecutor.shutdown();
    try {
      if (!outputProcessorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        LOG.error("Output processor executor did not shutdown cleanly.");
      }
    } catch (final InterruptedException e) {
      LOG.error("Interrupted while already shutting down", e);
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isActive(final String nodeName) {
    final Process process = besuProcesses.get(nodeName);
    return process != null && process.isAlive();
  }

  private void killBesuProcess(final String name, final Process process) {
    LOG.info("Killing " + name + " process");

    Process p = besuProcesses.remove(name);
    if (p == null) {
      LOG.error("Process {} wasn't in our list", name);
    }

    process.destroy();
    try {
      process.waitFor(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      LOG.warn("Wait for death of process " + name + " was interrupted", e);
    }
    if (process.isAlive()) {
      LOG.warn("Process {} still alive, destroying forcibly now", name);
      try {
        process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
      } catch (Exception e) {
        // just die already
      }
      LOG.info("Process exited with code {}", process.exitValue());
    }
  }
}

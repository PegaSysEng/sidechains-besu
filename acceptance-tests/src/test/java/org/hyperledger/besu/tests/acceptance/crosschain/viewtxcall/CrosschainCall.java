/*
 * Copyright 2019 ConsenSys AG.
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
package org.hyperledger.besu.tests.acceptance.crosschain.viewtxcall;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.tests.acceptance.crosschain.common.CrosschainAcceptanceTestBase;
import org.hyperledger.besu.tests.acceptance.crosschain.viewtxcall.generated.Bar2Ctrt;
import org.hyperledger.besu.tests.acceptance.crosschain.viewtxcall.generated.BarCtrt;
import org.hyperledger.besu.tests.acceptance.crosschain.viewtxcall.generated.FooCtrt;
import org.hyperledger.besu.tests.acceptance.dsl.account.Accounts;
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode;
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.besu.JsonRpc2_0Besu;
import org.web3j.protocol.besu.response.crosschain.CrossIsLockedResponse;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.CrosschainContext;
import org.web3j.tx.CrosschainContextGenerator;
import org.web3j.tx.CrosschainTransactionManager;

/*
 * Two contracts - BarCtrt and FooCtrt are deployed on blockchains 1 and 2 respectively. Many tests are created
 * to check crosschain transactions happening between views, pure functions and transactions. Nesting the calls
 * are also checked.
 */

public class CrosschainCall extends CrosschainAcceptanceTestBase {
  private static final Logger LOG = LogManager.getLogger();
  private BarCtrt barCtrt;
  private FooCtrt fooCtrt;
  private Bar2Ctrt bar2Ctrt;

  private Cluster clusterBc3;
  private BesuNode nodeOnBlockchain3;
  private CrosschainTransactionManager transactionManagerBlockchain3;

  @Before
  public void setUp() throws Exception {

    setUpCoordiantionChain();
    setUpBlockchain1();
    setUpBlockchain2();

    // Deploying BarCtrt on BlockChain1, and FooCtrt on BlockChain2
    barCtrt =
        nodeOnBlockchain1.execute(
            contractTransactions.createLockableSmartContract(
                BarCtrt.class, this.transactionManagerBlockchain1));
    fooCtrt =
        nodeOnBlockchain2.execute(
            contractTransactions.createLockableSmartContract(
                FooCtrt.class, this.transactionManagerBlockchain2));

    // Making nodeOnBlockChain1 a multichain node
    addMultichainNode(nodeOnBlockchain1, nodeOnBlockchain2);

    // Calling BooCtrt.setProperties, a regular intrachain function call
    barCtrt.setProperties(nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress()).send();
    // Calling FooCtrt.setPropertiesForBar, a regular intrachain function call
    fooCtrt
        .setPropertiesForBar(nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress())
        .send();
  }

  @After
  public void closeDown() throws Exception {
    this.cluster.close();
    this.clusterBc1.close();
    this.clusterBc2.close();
  }

  private void waitForUnlock(final String ctrtAddress, final BesuNode node) throws Exception {
    CrossIsLockedResponse isLockedObj =
        node.getJsonRpc()
            .crossIsLocked(ctrtAddress, DefaultBlockParameter.valueOf("latest"))
            .send();
    while (isLockedObj.isLocked()) {
      Thread.sleep(100);
      isLockedObj =
          node.getJsonRpc()
              .crossIsLocked(ctrtAddress, DefaultBlockParameter.valueOf("latest"))
              .send();
    }
  }

  private void setUpBlockchain3() throws Exception {
    this.nodeOnBlockchain3 = besu.createCrosschainBlockchain3Ibft2Node("bc3-node");
    this.clusterBc3 = new Cluster(this.net);
    this.clusterBc3.start(this.nodeOnBlockchain3);

    JsonRpc2_0Besu blockchain3Web3j = this.nodeOnBlockchain3.getJsonRpc();
    final Credentials BENEFACTOR_ONE = Credentials.create(Accounts.GENESIS_ACCOUNT_ONE_PRIVATE_KEY);
    JsonRpc2_0Besu coordinationWeb3j = this.nodeOnCoordinationBlockchain.getJsonRpc();

    this.transactionManagerBlockchain3 =
        new CrosschainTransactionManager(
            blockchain3Web3j,
            BENEFACTOR_ONE,
            this.nodeOnBlockchain3.getChainId(),
            BLOCKCHAIN1_RETRY_ATTEMPTS,
            BLOCKCHAIN1_SLEEP_DURATION,
            coordinationWeb3j,
            this.nodeOnCoordinationBlockchain.getChainId(),
            this.coordContract.getContractAddress(),
            CROSSCHAIN_TRANSACTION_TIMEOUT);
  }

  /*
   * FooCtrt has a simple view function called foo() that does nothing but returns 1. BarCtrt has a function called
   * bar() and a flag that is set to 0 while deploying (thanks to the constructor). bar() updates the flag with the
   * return value of foo(). The test doCCViewCall checks if the flag is set to 1 after the crosschain view call.
   */
  @Test
  public void doCCViewCall() throws Exception {
    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress());
    byte[] subordTrans = fooCtrt.foo_AsSignedCrosschainSubordinateView(subordTxCtx);
    byte[][] subordTxAndViews = new byte[][] {subordTrans};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordTxAndViews);

    // LOG.info("Flag value = {}", barCtrt.flag().send().longValue());
    TransactionReceipt txReceipt = barCtrt.bar_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(barCtrt.getContractAddress(), nodeOnBlockchain1);
    LOG.info("Flag value After = {}", barCtrt.flag().send().longValue());
    assertThat(barCtrt.flag().send().longValue()).isEqualTo(1);
  }

  /*
   * FooCtrt also has a updateState function that updates the state (fooFlag) to 1, and BarCtrt has a barUpdateState
   * function call, that calls the FooCtrt's updateState function. The doCCTxCall tests this
   * (BarCtrt.barUpdateState) crosschain transaction by checking if the fooFlag is set to 1 after the transaction.
   */
  @Test
  public void doCCTxCall() throws Exception {
    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress());
    byte[] subordTrans = fooCtrt.updateState_AsSignedCrosschainSubordinateTransaction(subordTxCtx);
    byte[][] subordTxAndViews = new byte[][] {subordTrans};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordTxAndViews);

    TransactionReceipt txReceipt = barCtrt.barUpdateState_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(fooCtrt.getContractAddress(), this.nodeOnBlockchain2);
    assertThat(fooCtrt.fooFlag().send().longValue()).isEqualTo(1);
  }

  /*
   * Similar to doCCViewCall(), however a pure function is used in place of a view function.
   */
  @Test
  public void doCCPureCall() throws Exception {
    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress());
    byte[] subordTrans = fooCtrt.pureFoo_AsSignedCrosschainSubordinateView(subordTxCtx);
    byte[][] subordTxAndViews = new byte[][] {subordTrans};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordTxAndViews);

    TransactionReceipt txReceipt = barCtrt.pureBar_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(barCtrt.getContractAddress(), this.nodeOnBlockchain1);
    assertThat(barCtrt.flag().send().longValue()).isEqualTo(2);
  }

  /*
   * Checks the nested crosschain view calls.
   */
  @Test
  public void doCCViewViewCall() throws Exception {
    // Making nodeOnBlockChain2 a 2-chain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());

    LOG.info("Constructing Nested Crosschain Transaction");
    // BarCtrt.viewfn() is called by FooCtrt.foovv()
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress());
    byte[] subordView1 = barCtrt.viewfn_AsSignedCrosschainSubordinateView(subordTxCtx);

    // FooCtrt.foovv() is called by BarCtrt.barvv()
    byte[][] subordViewForFooVv = new byte[][] {subordView1};
    subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress(), subordViewForFooVv);
    byte[] subordView2 = fooCtrt.foovv_AsSignedCrosschainSubordinateView(subordTxCtx);

    byte[][] subordViews = new byte[][] {subordView2};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordViews);

    TransactionReceipt txReceipt = barCtrt.barvv_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(barCtrt.getContractAddress(), nodeOnBlockchain1);
    assertThat(barCtrt.vvflag().send().longValue()).isEqualTo(1);
  }

  /*
   * Checks the nested crosschain view to pure calls.
   */
  @Test
  public void doCCViewPureCall() throws Exception {
    // Making nodeOnBlockChain2 a 2-chain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());

    LOG.info("Constructing Nested Crosschain Transaction");
    // BarCtrt.purefn() is called by FooCtrt.foovp()
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress());
    byte[] subordView1 = barCtrt.purefn_AsSignedCrosschainSubordinateView(subordTxCtx);

    // FooCtrt.foovp() is called by BarCtrt.barvp()
    byte[][] subordViewForFooVp = new byte[][] {subordView1};
    subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress(), subordViewForFooVp);
    byte[] subordView2 = fooCtrt.foovp_AsSignedCrosschainSubordinateView(subordTxCtx);

    byte[][] subordViews = new byte[][] {subordView2};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordViews);

    TransactionReceipt txReceipt = barCtrt.barvp_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(barCtrt.getContractAddress(), nodeOnBlockchain1);
    assertThat(barCtrt.vpflag().send().longValue()).isEqualTo(1);
  }

  /*
   * Checks the nested crosschain transaction to view calls.
   */
  @Test
  public void doCCTxViewCall() throws Exception {
    // Making nodeOnBlockChain2 a 2-chain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());

    LOG.info("Constructing Nested Crosschain Transaction");
    // BarCtrt.viewfn() is called by FooCtrt.footv()
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress());
    byte[] subordView1 = barCtrt.viewfn_AsSignedCrosschainSubordinateView(subordTxCtx);

    // FooCtrt.updateStateFromView() is called by BarCtrt.bartv()
    byte[][] subordViewForFooTv = new byte[][] {subordView1};
    subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress(), subordViewForFooTv);
    byte[] subordView2 =
        fooCtrt.updateStateFromView_AsSignedCrosschainSubordinateTransaction(subordTxCtx);

    byte[][] subordViews = new byte[][] {subordView2};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordViews);

    TransactionReceipt txReceipt = barCtrt.bartv_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(fooCtrt.getContractAddress(), nodeOnBlockchain2);
    assertThat(fooCtrt.tvFlag().send().longValue()).isEqualTo(1);
  }

  /*
   * Checks the nested crosschain transaction to pure calls.
   */
  @Test
  public void doCCTxPureCall() throws Exception {
    // Making nodeOnBlockChain2 a 2-chain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    CrosschainContextGenerator ctxGenerator =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());

    LOG.info("Constructing Nested Crosschain Transaction");
    // BarCtrt.purefn() is called by FooCtrt.footp()
    CrosschainContext subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress());
    byte[] subordView1 = barCtrt.purefn_AsSignedCrosschainSubordinateView(subordTxCtx);

    // FooCtrt.updateStateFromView() is called by BarCtrt.bartv()
    byte[][] subordViewForFooTp = new byte[][] {subordView1};
    subordTxCtx =
        ctxGenerator.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress(), subordViewForFooTp);
    byte[] subordView2 =
        fooCtrt.updateStateFromPure_AsSignedCrosschainSubordinateTransaction(subordTxCtx);

    byte[][] subordViews = new byte[][] {subordView2};
    CrosschainContext origTxCtx = ctxGenerator.createCrosschainContext(subordViews);

    TransactionReceipt txReceipt = barCtrt.bartp_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    waitForUnlock(fooCtrt.getContractAddress(), nodeOnBlockchain2);
    assertThat(fooCtrt.tpFlag().send().longValue()).isEqualTo(1);
  }

  /*
   * Checks the nested crosschain transaction to transaction and view calls. This case is similar to the
   * nested transactions example used in SDLT 2019 presentation of 'Application Level Authentication for
   * Ethereum Private Blockchain Atomic Crosschain transactions'
   */
  @Test
  public void doCCTxTxViewCall() throws Exception {
    setUpBlockchain3();

    // Deploying Bar2Ctrt on blockchain3
    bar2Ctrt =
        nodeOnBlockchain3.execute(
            contractTransactions.createLockableSmartContract(
                Bar2Ctrt.class, this.transactionManagerBlockchain3));

    // Calling FooCtrt.setPropertiesForBar2, a regular intrachain function call
    fooCtrt
        .setPropertiesForBar2(nodeOnBlockchain3.getChainId(), bar2Ctrt.getContractAddress())
        .send();

    // Making nodeOnBlockChain1 a 3-chain node
    addMultichainNode(nodeOnBlockchain1, nodeOnBlockchain3);

    // Making nodeOnBlockchain2 a 3-chain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain3);
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    CrosschainContextGenerator ctxGen =
        new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());

    // BarCtrt.viewfn() is called by FooCtrt.updateStateFromTxView()
    CrosschainContext ctx1 =
        ctxGen.createCrosschainContext(
            nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress());
    byte[] subView1 = barCtrt.viewfn_AsSignedCrosschainSubordinateView(ctx1);
    byte[] subTx1 = bar2Ctrt.updateState_AsSignedCrosschainSubordinateTransaction(ctx1);

    // FooCtrt.updateStateFromTxView() is called by BarCtrt.barttv()
    byte[][] subTxViews1 = new byte[][] {subView1, subTx1};
    CrosschainContext ctx2 =
        ctxGen.createCrosschainContext(
            nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress(), subTxViews1);
    byte[] subTxViews2 =
        fooCtrt.updateStateFromTxView_AsSignedCrosschainSubordinateTransaction(ctx2);

    byte[][] subordTxs = new byte[][] {subTxViews2};
    CrosschainContext origTxCtx = ctxGen.createCrosschainContext(subordTxs);

    TransactionReceipt txReceipt = barCtrt.barttv_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    // Check for the correctness of the innermost view
    waitForUnlock(fooCtrt.getContractAddress(), nodeOnBlockchain2);
    assertThat(fooCtrt.ttvFlag().send().longValue()).isEqualTo(1);

    // Check for the update of the innermost transaction
    waitForUnlock(bar2Ctrt.getContractAddress(), nodeOnBlockchain3);
    assertThat(bar2Ctrt.ttvflag().send().longValue()).isEqualTo(1);

    this.clusterBc3.close();
  }

  /*
   * Check that the transaction on a locked contract should fail.
   */
  @Ignore
  public void doCCTxTxViewCallOn2Chains() throws Exception {
    // Deploying Bar2Ctrt on blockchain1
    bar2Ctrt =
            nodeOnBlockchain1.execute(
                    contractTransactions.createLockableSmartContract(
                            Bar2Ctrt.class, this.transactionManagerBlockchain1));

    // Calling FooCtrt.setPropertiesForBar2, a regular intrachain function call
    fooCtrt
            .setPropertiesForBar2(nodeOnBlockchain1.getChainId(), bar2Ctrt.getContractAddress())
            .send();

    // Making nodeOnBlockChain1 a 3-chain node
    addMultichainNode(nodeOnBlockchain1, nodeOnBlockchain3);

    // Making nodeOnBlockchain2 a 3-chain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain3);
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    CrosschainContextGenerator ctxGen =
            new CrosschainContextGenerator(nodeOnBlockchain1.getChainId());

    // BarCtrt.viewfn() is called by FooCtrt.updateStateFromTxView()
    CrosschainContext ctx1 =
            ctxGen.createCrosschainContext(
                    nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress());
    byte[] subView1 = barCtrt.viewfn_AsSignedCrosschainSubordinateView(ctx1);
    byte[] subTx1 = bar2Ctrt.updateState_AsSignedCrosschainSubordinateTransaction(ctx1);

    // FooCtrt.updateStateFromTxView() is called by BarCtrt.barttv()
    byte[][] subTxViews1 = new byte[][] {subView1, subTx1};
    CrosschainContext ctx2 =
            ctxGen.createCrosschainContext(
                    nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress(), subTxViews1);
    byte[] subTxViews2 =
            fooCtrt.updateStateFromTxView_AsSignedCrosschainSubordinateTransaction(ctx2);

    byte[][] subordTxs = new byte[][] {subTxViews2};
    CrosschainContext origTxCtx = ctxGen.createCrosschainContext(subordTxs);

    TransactionReceipt txReceipt = barCtrt.barttv_AsCrosschainTransaction(origTxCtx).send();
    if (!txReceipt.isStatusOK()) {
      LOG.info("txReceipt details " + txReceipt.toString());
      throw new Error(txReceipt.getStatus());
    }

    // Check for the correctness of the innermost view
    waitForUnlock(fooCtrt.getContractAddress(), nodeOnBlockchain2);
    assertThat(fooCtrt.ttvFlag().send().longValue()).isEqualTo(1);

    // Check for the update of the innermost transaction
    waitForUnlock(bar2Ctrt.getContractAddress(), nodeOnBlockchain3);
    assertThat(bar2Ctrt.ttvflag().send().longValue()).isEqualTo(1);

    this.clusterBc3.close();
  }
}

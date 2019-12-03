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
import org.hyperledger.besu.tests.acceptance.crosschain.viewtxcall.generated.BarCtrt;
import org.hyperledger.besu.tests.acceptance.crosschain.viewtxcall.generated.FooCtrt;
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.web3j.protocol.besu.response.crosschain.CrossIsLockedResponse;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.CrosschainContext;
import org.web3j.tx.CrosschainContextGenerator;

/*
 * Two contracts - BarCtrt and FooCtrt are deployed on blockchains 1 and 2 respectively. Many tests are created
 * to check crosschain transactions happening between views, pure functions and transactions. Nesting the calls
 * are also checked.
 */

public class CrosschainCall extends CrosschainAcceptanceTestBase {
  private final Logger LOG = LogManager.getLogger();
  private BarCtrt barCtrt;
  private FooCtrt fooCtrt;

  @Before
  public void setUp() throws Exception {

    setUpCoordiantionChain();
    setUpBlockchain1();
    setUpBlockchain2();

    // Making nodeOnBlockChain1 a multichain node
    addMultichainNode(nodeOnBlockchain1, nodeOnBlockchain2);
    // Making nodeOnBlockchain2 a multichain node
    addMultichainNode(nodeOnBlockchain2, nodeOnBlockchain1);

    // Deploying BarCtrt on BlockChain1 and FooCtrt on BlockChain2
    barCtrt =
        nodeOnBlockchain1.execute(
            contractTransactions.createLockableSmartContract(
                BarCtrt.class, this.transactionManagerBlockchain1));
    fooCtrt =
        nodeOnBlockchain2.execute(
            contractTransactions.createLockableSmartContract(
                FooCtrt.class, this.transactionManagerBlockchain2));

    // Calling BooCtrt.setProperties, a regular intrachain function call
    barCtrt.setProperties(nodeOnBlockchain2.getChainId(), fooCtrt.getContractAddress()).send();
    // Calling FooCtrt.setProperties, a regular intrachain function call
    fooCtrt.setProperties(nodeOnBlockchain1.getChainId(), barCtrt.getContractAddress()).send();
  }

  @After
  public void closeDown() throws Exception {
    this.cluster.close();
    this.clusterBc1.close();
    this.clusterBc2.close();
  }

  private void waitForUnlock(final String ctrtAddress, @NotNull final BesuNode node)
      throws Exception {
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
}

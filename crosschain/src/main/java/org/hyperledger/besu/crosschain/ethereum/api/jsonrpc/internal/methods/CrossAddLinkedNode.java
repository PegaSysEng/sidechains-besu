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
package org.hyperledger.besu.crosschain.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.crosschain.core.CrosschainController;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;

import java.math.BigInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Sets the address of the key generation contract to use. */
public class CrossAddLinkedNode implements JsonRpcMethod {

  private static final Logger LOG = LogManager.getLogger();

  private final CrosschainController crosschainController;
  private final JsonRpcParameter parameters;

  public CrossAddLinkedNode(
      final CrosschainController crosschainController, final JsonRpcParameter parameters) {
    this.crosschainController = crosschainController;
    this.parameters = parameters;
  }

  @Override
  public String getName() {
    return RpcMethod.CROSS_ADD_LINKED_NODE.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequest request) {
    if (request.getParamLength() != 2) {
      return new JsonRpcErrorResponse(request.getId(), JsonRpcError.INVALID_PARAMS);
    }
    final BigInteger blockchainId =
        this.parameters.required(request.getParams(), 0, BigInteger.class);
    final String ipAddressAndPort = this.parameters.required(request.getParams(), 1, String.class);

    // TODO check that ipAddressAndPort is valid

    LOG.trace(
        "JSON RPC {}: Blockchain Id: {}, , IPAddress: {}",
        getName(),
        blockchainId,
        ipAddressAndPort);

    this.crosschainController.addLinkedNode(blockchainId, ipAddressAndPort);
    return new JsonRpcSuccessResponse(request.getId());
  }
}

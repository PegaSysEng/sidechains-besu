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
package org.hyperledger.besu.ethereum.mainnet;

import org.hyperledger.besu.ethereum.core.Address;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

/** Encapsulates a group of {@link PrecompiledContract}s used together. */
public class PrecompileContractRegistry {

  private final Table<Address, Integer, PrecompiledContract> precompiles;

  public PrecompileContractRegistry() {
    this.precompiles = HashBasedTable.create(16, 2);
  }

  public PrecompiledContract get(final Address address, final int contractAccountVersion) {
//    // TODO SIDECHAINS is this code needed????  START
//    if (address.equals(Address.fromHexString("0xfa"))) {
//      return precompiles.get(Address.fromHexString("0xa"), contractAccountVersion);
//    }
//    // TODO SIDECHAINS is this code needed????  END
    return precompiles.get(address, contractAccountVersion);
  }

  public void put(
      final Address address,
      final int contractAccountVersion,
      final PrecompiledContract precompile) {
    precompiles.put(address, contractAccountVersion, precompile);
  }
}

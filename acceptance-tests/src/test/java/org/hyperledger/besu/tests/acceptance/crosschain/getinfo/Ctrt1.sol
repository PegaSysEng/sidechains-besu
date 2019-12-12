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

pragma solidity >=0.4.0 <0.6.0;
import "../common/Crosschain.sol";
import "./Ctrt2Int.sol";

contract Ctrt1 is Crosschain {
    uint256 ctrt2ChainId;
    Ctrt2Int ctrt2;

    uint256 public myChainId;
    uint32 public myTxType;
    uint256 public coordChainId;
    uint256 public origChainId;

    function setCtrt2ChainId(uint256 _ctrt2ChainId) public {
        ctrt2ChainId = _ctrt2ChainId;
    }

    function setCtrt2(address _ctrt2Addr) public {
        ctrt2 = Ctrt2Int(_ctrt2Addr);
    }

    function callCtrt2() public {
        crosschainTransaction(ctrt2ChainId, address(ctrt2), abi.encodeWithSelector(ctrt2.callCtrt3.selector));
        myChainId = crosschainGetInfoBlockchainId();
        myTxType = crosschainGetInfoTransactionType();
        coordChainId = crosschainGetInfoCoordinationBlockchainId();
        origChainId = crosschainGetInfoOriginatingBlockchainId();
    }
}
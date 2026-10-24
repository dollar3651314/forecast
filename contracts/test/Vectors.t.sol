// SPDX-License-Identifier: Apache-2.0
pragma solidity 0.8.20;

import {Test, Vm} from "forge-std/Test.sol";
import {ForwarderV4} from "bitgo/ForwarderV4.sol";
import {ForwarderFactoryV4} from "bitgo/ForwarderFactoryV4.sol";
import {DepositSalt} from "../src/DepositSalt.sol";

/// 地址算法三方一致（方案第 4.14 节）：测试向量文件里的 1,000 个地址，用真的 BitGo 工厂逐个部署核对。
/// 同一份文件由后端 Create2Test 核对 Java 的算法。
contract VectorsTest is Test {
    string constant FILE = "../backend/src/test/resources/wallet/create2-vectors.json";

    function test_VectorsMatchRealFactory() public {
        string memory json = vm.readFile(FILE);
        address factoryAt = vm.parseJsonAddress(json, ".factory");
        address implAt = vm.parseJsonAddress(json, ".implementation");
        address[] memory parents = vm.parseJsonAddressArray(json, ".parents");
        address[] memory fees = vm.parseJsonAddressArray(json, ".feeAddresses");
        uint256[] memory users = vm.parseJsonUintArray(json, ".userIds");
        bytes32[] memory salts = vm.parseJsonBytes32Array(json, ".salts");
        address[] memory addrs = vm.parseJsonAddressArray(json, ".addresses");
        assertEq(addrs.length, 1000);

        deployCodeTo("ForwarderV4.sol:ForwarderV4", implAt);
        deployCodeTo("ForwarderFactoryV4.sol:ForwarderFactoryV4", abi.encode(implAt), factoryAt);
        ForwarderFactoryV4 factory = ForwarderFactoryV4(factoryAt);
        assertEq(factory.implementationAddress(), implAt);

        for (uint256 i; i < addrs.length; ++i) {
            assertEq(DepositSalt.forUser(users[i]), salts[i], "salt");
            vm.recordLogs();
            factory.createForwarder(parents[i], fees[i], salts[i]);
            Vm.Log[] memory logs = vm.getRecordedLogs();
            (address created,,,,) = abi.decode(logs[logs.length - 1].data, (address, address, address, bool, bool));
            assertEq(created, addrs[i], "address");
            assertEq(ForwarderV4(payable(created)).parentAddress(), parents[i]);
        }
    }
}

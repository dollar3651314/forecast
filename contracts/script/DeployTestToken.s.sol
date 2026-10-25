// SPDX-License-Identifier: Apache-2.0
pragma solidity 0.8.20;

import {Script, console} from "forge-std/Script.sol";
import {MockUsdtNoReturn} from "../test/Mocks.sol";

/// 只给本地 anvil 端到端测试用（anvil-e2e.sh）：部署一个和以太坊主网 USDT 一样 transfer 不返回 bool 的测试代币。不要在测试网、主网运行。
contract DeployTestToken is Script {
    function run() external {
        require(block.chainid == 31337, unicode"只能在本地 anvil 上运行");
        vm.startBroadcast();
        MockUsdtNoReturn t = new MockUsdtNoReturn();
        vm.stopBroadcast();
        console.log("TEST_USDT=", address(t));
    }
}

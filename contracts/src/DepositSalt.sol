// SPDX-License-Identifier: Apache-2.0
pragma solidity 0.8.20;

/// 充值地址的盐（技术方案/v2-半自动归集.md 第 4.13.1 节）：keccak256("forecast-deposit" ‖ 用户 ID 的 32 字节大端整数)。
/// 只给测试和脚本用（internal，不单独部署）；后端 Java 用同一算法（wallet/address/Create2.java），测试向量交叉核对。
library DepositSalt {
    function forUser(uint256 userId) internal pure returns (bytes32) {
        return keccak256(abi.encodePacked("forecast-deposit", userId));
    }
}

# 资金归集合约（以太坊、BSC）

- 方案：`技术方案/v2-半自动归集.md` v1.2 第 4.1、4.13、4.14 节（路线 (b)：BitGo 转发合约原样部署 + 我们自己写的批量合约，钱只能进 Safe 冷钱包）。
- 部署：Amos 在自己的电脑上运行，一步一步的说明见 `部署方案/v2-归集合约部署.md`。
- 工具：Foundry（forge / anvil / cast，CI 用 v1.5.1）。

## 1. 目录

| 路径 | 是什么 |
|---|---|
| `lib/eth-multisig-v4/` | BitGo `eth-multisig-v4` 的 6 个合约文件 + LICENSE，**原样复制，一行不改** |
| `lib/openzeppelin-contracts/` | OpenZeppelin Contracts v5.0.0（BitGo 依赖的版本）里用到的 11 个文件 + LICENSE，原样复制 |
| `lib/SHA256SUMS` | 上面每个文件的 sha256；`script/check-vendor.sh` 在 CI 里核对，`--upstream` 时再从 GitHub 拉上游逐字节比较 |
| `lib/forge-std/` | Foundry 测试库（git submodule，固定 v1.11.0 = `8e40513d678f392f398620b3ef2b418648b33e89`），只用于测试和脚本，不部署 |
| `src/SweepBatcher.sol` | 批量合约（我们写的，约 60 行有效代码） |
| `src/DepositSalt.sol` | 充值地址的盐（只给测试和脚本用，不部署） |
| `test/` | 单元、模糊、不变量测试，CREATE2 测试向量核对 |
| `script/Deploy.s.sol` | 部署（Amos 运行） |
| `script/VerifyDeployment.s.sol` | 部署后连链上核对字节码和参数（只读） |
| `script/GenVectors.s.sol` | 生成 1,000 个 CREATE2 测试向量（`backend/src/test/resources/wallet/create2-vectors.json`） |
| `script/DeployTestToken.s.sol`、`script/anvil-e2e.sh` | 本地 anvil 端到端测试（只在本地链上用） |
| `script/check-vendor.sh` | 核对原样源码 |

## 2. 来源（原样部署，方案第 4.14 节）

| 来源 | 版本 | 许可证 |
|---|---|---|
| https://github.com/BitGo/eth-multisig-v4 | 提交 `9566bcd47663a9ad7b71cf4c75466ccaa19fc7d7`（2026-10-16，master；6 个合约文件在这个提交上的最后一次修改是 `0d97e3850bacf8ddacdf7b7ff96fe65b315aa78d`） | Apache-2.0（`CloneFactory.sol` 是 MIT） |
| https://github.com/OpenZeppelin/openzeppelin-contracts | 标签 `v5.0.0` = `932fddf69a699a9a80fd2396fd1a2ab91cdda123`（BitGo `package.json` 写的 `@openzeppelin/contracts` 5.0.0） | MIT |

每个文件的 sha256（和 `lib/SHA256SUMS` 相同；2026-10-20 用 `bash script/check-vendor.sh --upstream` 和上游逐字节比较过，一致）：

| 文件 | sha256 |
|---|---|
| eth-multisig-v4/LICENSE | `6dc0e068dcf3a5bc8e054205b85b7720e1d49265bbc64bf515d2cf79197df69a` |
| eth-multisig-v4/contracts/CloneFactory.sol | `34d61f314f2d04ca371bdb7548950de260617a3e40eaa89e9833d9d6dcef524a` |
| eth-multisig-v4/contracts/ERC20Interface.sol | `045431c9efb8639d8e6a6f1a4495a1f3a11a3c9c7334aa00d56005296d74500e` |
| eth-multisig-v4/contracts/ForwarderFactoryV4.sol | `9ac180c49d51acc87c408f321ebb83cb8801cec70190e938b40a9fefe0050f9c` |
| eth-multisig-v4/contracts/ForwarderV4.sol | `52400b97ee2906e0aceea8795e7946b5bca8046b5a1bb0f96a4127796d48210e` |
| eth-multisig-v4/contracts/IForwarderV4.sol | `1d89d358a8b366f2dc60a58689d85436fd1c30b78096443bb311bcadd61957db` |
| eth-multisig-v4/contracts/TransferHelper.sol | `0156f5b8d3682621c36f3e78d301938c6b3802c14af958b7fe5565ddcb121f79` |
| openzeppelin-contracts/LICENSE | `0e05b4f45c8769ece14ba2d202bf6f5ed7132600b642c981266f7255e0187ea3` |
| openzeppelin-contracts/contracts/access/Ownable.sol | `38578bd71c0a909840e67202db527cc6b4e6b437e0f39f0c909da32c1e30cb81` |
| openzeppelin-contracts/contracts/access/Ownable2Step.sol | `90f1f1cdd07ce4b90e987065e82899fdaa6ef967d1996915143c6e39818e160c` |
| openzeppelin-contracts/contracts/token/ERC1155/IERC1155.sol | `1c202ce569e0cd31dbbdd2338783b4ac7e60fefa45cd3718d86507294246a8cc` |
| openzeppelin-contracts/contracts/token/ERC1155/IERC1155Receiver.sol | `a44a901c06e11f4b5d2d03753d8552bf0ebc13ff5e880922c64704b12182589e` |
| openzeppelin-contracts/contracts/token/ERC1155/utils/ERC1155Holder.sol | `b121da3ff0077feeb73e0ffb033625c82179ce1df2fde0bcc7703a6998cfa7ec` |
| openzeppelin-contracts/contracts/token/ERC721/IERC721.sol | `8239cba58986af2deb65af8092d5abcd4ffe60a38dee00cb2e8c6465f945c2d0` |
| openzeppelin-contracts/contracts/token/ERC721/IERC721Receiver.sol | `ad84633afd30a71a888403875e2ca7db9e97499d258ccdd98f48dd7cea8229f5` |
| openzeppelin-contracts/contracts/utils/Address.sol | `b3710b1712637eb8c0df81912da3450da6ff67b0b3ed18146b033ed15b1aa3b9` |
| openzeppelin-contracts/contracts/utils/Context.sol | `9c1cc43aa4a2bde5c7dea0d4830cd42c54813ff883e55c8d8f12e6189bf7f10a` |
| openzeppelin-contracts/contracts/utils/introspection/ERC165.sol | `99348354365cbdeb90157e2903334b861a00d69faab7720ae542d911d5c70d87` |
| openzeppelin-contracts/contracts/utils/introspection/IERC165.sol | `07ae1ac964ab74dedada999e2dfc642031a6495469cffc0bf715daa4f1e4f904` |

编译参数和 BitGo 仓库的 `hardhat.config.ts` 一致：solc **0.8.20**、evmVersion **paris**、优化器开、**1000** 次（`foundry.toml`）。我们的批量合约用同一组参数。

**为什么用"固定提交号的原样复制"而不是 submodule**：部署上链的代码要能在仓库里直接审阅、每个文件有 sha256，CI 不依赖访问 GitHub 也能核对；只复制用到的文件（不带 BitGo 的 hardhat 配置等），`--upstream` 随时可以和上游逐字节比。forge-std 只用于测试，用 submodule。

## 3. 批量合约 SweepBatcher

- `sweep(token, salts[])`：只有 owner 登记的发起账户（`isOperator`）能调用；对每个盐，转发地址没部署就调用 BitGo 工厂 `createForwarder(parent, address(this), salt)`，然后调用转发合约的 `flushTokens(token)`，代币由 **BitGo 的代码**转给部署时写死的 parent。每个地址单独 `try`：失败只发 `SweepFailed`，不影响同一批的其他地址。
- `forwarderAddress(salt)`：和工厂部署出的地址相同（模糊测试 10,000 次核对）。
- `setOperator(operator, allowed)`：只有 owner。owner 用 OpenZeppelin `Ownable2Step`：部署脚本先 `transferOwnership(Safe)`，Safe 签 `acceptOwnership` 才生效；`renounceOwnership` 被禁用（放弃后就换不了发起账户）。
- **碰不到钱**：不收原生币（没有 receive / payable），不持有任何代币，没有能把钱转到任意地址的函数，不用 `delegatecall`、不可升级。它是所有转发地址的 `feeAddress`，所以 BitGo 转发合约允许它调用 `flushTokens`、`batchFlushERC20Tokens`、NFT 的 flush 等函数——这些函数的收款方都是 parent；本合约只暴露了 `flushTokens`。`callFromParent`（能让转发合约调用任意合约）只有 parent 能调用，本合约不能。

## 4. 实测结论（`test/SweepBatcher.t.sol`，2026-10-20 实际运行）

| 问题 | 结论 | 测试 |
|---|---|---|
| parent 能不能直接调用转发合约的 `flushTokens`（方案里"未核实"） | **能**。`onlyAllowedAddress` = parent 或 feeAddress；Safe 执行一笔调用就能把某个地址的余额转到 parent。发起账户（EOA）和其他人都不能直接调用（`Address is not allowed`） | `test_ParentCanFlushDirectly`；anvil 端到端也实测了 |
| `createForwarder` 是不是任何人都能调用（方案里"未核实"） | **是**，工厂没有权限检查。所以批量合约坏了时，Safe 可以自己先 `createForwarder(parent, 批量合约, salt)` 部署、再 `flushTokens` | `test_AnyoneCanCreateForwarder_SafeDeploysThenFlushes` |
| 任何人都能部署，会不会被抢先部署 | **无害**。别人用同样的参数部署出的是同一个地址、同样的 parent 和 feeAddress（还替我们付了 gas）；5 个参数的版本可以关掉自动转 NFT，但这两个开关不在盐里、不影响地址和 ERC20 归集。换成自己的 parent 部署出的是另一个地址，碰不到我们的。批量合约先看地址有没有代码再决定要不要部署，被抢先部署时直接归集。**注意**：同一个地址第二次部署会耗光转给它的 gas（CREATE2 冲突），所以"先看有没有代码"不能省 | `test_FrontRunDeployIsHarmless` |
| 实现合约本身没有初始化，任何人都能 `init` 它 | 无害：转发地址用自己的存储，实现合约没有 selfdestruct，里面也没有钱 | `test_InitializingImplementationDoesNotAffectForwarders` |
| 换发起账户后老地址怎么办 | **不用换地址**：发起账户不在地址算法里。Safe 在批量合约里 `setOperator(旧, false)`、`setOperator(新, true)`，新账户照样能部署和归集老地址，旧账户被拒绝 | `test_OperatorRotationKeepsOldAddresses` |
| 换批量合约（= 换 feeAddress） | 同一个盐算出的是**另一个地址**；老地址只认老批量合约，新批量合约调用老地址的 `flushTokens` 被拒。所以**老批量合约要一直保留**用来归集老地址（后端按每个地址记的"第几套参数"选批量合约） | `test_NewBatcherCannotSweepOldAddresses` |
| 以太坊主网 USDT（transfer 不返回 bool） | 正常归集（BitGo `TransferHelper` 允许没有返回值） | `MockUsdtNoReturn`，所有用例；anvil 端到端 |
| BSC USDT（标准 ERC20、18 位小数） | 正常归集 | `test_Bsc18DecimalUsdt` |
| 返回 false / 回滚的代币 | 这个地址发 `SweepFailed`，同一批的其他地址照常 | `test_OneFailureDoesNotAffectOthers` |
| 客户误转原生币（ETH / BNB） | 没部署时先存在地址上，部署时 `init` 自动转给 parent；部署后再转直接转给 parent；被强制塞进来的（selfdestruct）任何人都可以调用 `flush()` 转给 parent。用只给 2,300 gas 的 `transfer` 往已部署地址转会失败（我们不收原生币充值） | `test_StrayNativeCoin`、`test_Transfer2300ToDeployedForwarderFails` |
| 客户误转其他 ERC20 | 同一个批量合约换个代币地址就能归集到 parent（不入账） | `test_StrayOtherErc20` |

## 5. 金额上限放在哪里：**后端（链下）**

上线初期的上限（每个地址 5,000、每批 20,000 USDT，后台可改，Amos 10-20 保留）在后端预览时检查（`backend/.../wallet/sweep/SweepService.java`），合约里没有。理由：

1. **上限防的是"万一有没发现的问题"造成的损失**，而这类问题里最危险的"钱去了别处"在我们的批量合约里不可能发生：钱的去向是 BitGo 代码里写死的 parent，批量合约只决定"哪些地址、什么时候"。超过上限的钱如果被归集了，也只是进了 Safe。
2. 合约里做上限要多出：读余额、累加、比较、上限的设置函数和权限——都是新代码和新的 owner 操作，正好和"批量合约尽量短"（方案第 4.14 节）冲突；上限还要能在后台随时调（Amos 10-20："后台可改"），放在合约里每次调整都要 Safe 签名。
3. `flushTokens` 总是转走全部余额，合约里的上限只能"整个地址跳过"，和后端做的一样，没有多保护什么。
4. 剩余风险：生成预览和交易上链之间新到的钱会一起转走（几秒到几分钟），可能略超单地址上限——钱仍然只进 Safe。服务器被攻破的人可以绕过后端上限，但他同样只能把钱送进 Safe。

## 6. 测试和实测数据（2026-10-20 实际运行，forge 1.5.1）

- `forge test`：30 个全部通过——`SweepBatcherTest` 25 个（含模糊测试 3 个，每个 10,000 次）、不变量测试 4 个（256 轮 × 64 步：批量合约永远不持有代币、代币只在转发地址和 parent 之间、非发起账户一次也调用不成功、parent 余额只增不减）、`VectorsTest` 1 个（1,000 个测试向量用真实 BitGo 工厂逐个部署核对）。
- `forge coverage`：`src/SweepBatcher.sol` 行 30/30、语句 33/33、分支 7/7、函数 5/5，**全部 100%**（CI 卡住）。
- gas（anvil 本地链实测，真实交易回执，EIP-1559）：**1 个地址首次（部署 + 归集）整笔 157,128；已部署整笔 50,230**。Foundry 测试里一笔交易内的增量（存储已预热，偏低，只作比例参考）：每个地址首次约 10.3 万、已部署约 0.9 万。后端预估用的是每个首次地址 16 万、已部署 4.5 万、每笔另加 4 万（`application.yml` 的 `forecast.wallet.sweep.evm.*-gas`），留了余量；方案第 3.2 节 BitGo 文档写的"部署约 25 万"是 BitGo 自己的组合，我们实测更低。
- 以太坊主网 / BSC 主网分叉测试（方案第 4.11 节）：**没有做**（开发环境没有主网节点的稳定访问），用不返回 bool 的 `MockUsdtNoReturn` 和 18 位的 `MockErc20` 代替；测试网联调时用真实的测试 USDT 再验一次。

## 7. 常用命令

```bash
cd contracts
git submodule update --init          # 第一次：拉 forge-std
bash script/check-vendor.sh          # 原样源码 sha256（加 --upstream 和 GitHub 上游逐字节比）
forge build && forge test            # 编译、测试
forge coverage --report summary --no-match-coverage "(test|script|lib)"
forge script script/GenVectors.s.sol # 重新生成 CREATE2 测试向量（一般不需要）
bash script/anvil-e2e.sh             # 本地链端到端（需要 JDK 25、Maven、MySQL、Redis，见脚本开头）
```

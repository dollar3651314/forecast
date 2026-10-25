# SQ-8 充值地址并发分配死锁：修复记录
- 阶段：开发 S5 开始前的单独修复（`开发记录/S4-下注奖池.md` 第 7、9 节的 SQ-8，Amos 要求"开始前先修，单独一个提交，改动只限 `wallet/address`"）
- 分支：`claude/s5-settle`（从 `claude/s4-pool` 的 `5e419c8` 开出，`git fetch` 核对过远程，C68）
- 日期：2026-10-25
- 角色：研发工程师
- 依据：agents main `1f92bbc`（《研发工程师》v0.12.1，C37、C57；《测试工程师》C57、C80）
- 名词：**复制源**同 S1（代码和本文件里不写它的名字）。

## 1. 结论
- 症状：很多新客户同时第一次取充值地址（或同时注册）时，`AddressService.allocateAddresses` 偶发死锁，接口返回 500（S4 预压测时 20 个并发取地址就出现）。
- 根因：**对一个还不存在的行做 `SELECT … FOR UPDATE`**。可重复读下，这个查询在唯一索引 `uk_wallet_address_active` 上加的是**间隙锁**；新客户的 `active_key`（"用户ID:体系"）都落在索引里同一个间隙。间隙锁之间互相兼容，两个事务都拿到了；接着两个事务各自 `INSERT`，插入要的"插入意向锁"又都被对方的间隙锁挡住，InnoDB 判死锁、回滚其中一个。
- 修复（只改 `wallet/address`）：
  1. 分配时**不对可能不存在的行加锁读**：先普通读；没有就直接 `INSERT IGNORE`，由唯一键 `uk_wallet_address_active` 保证每人每个体系只有一个正在用的地址。插入只要插入意向锁，不同客户之间互不阻塞。
  2. 合约地址方案（`useForwarder`）：新客户同样直接插入；只有确实有一个旧地址要停用时，才对那一行（已存在，只加行锁）加排他锁。
  3. `addressOf`（打开充值页）不再包一个大事务：原来可重复读的快照建在第一次读时，同一客户另一个请求刚提交的地址读不到，分配完再读会读成"没有"（`NoSuchElementException`，修复前的测试里也出现 13 次）。现在每次读最新提交的数据，分配单独一个事务，事务开头先锁这个客户的账户行（`LedgerApi.lock`，和下注、提现同一个排队点），同一客户几个请求同时到时排队分配。万一仍被判死锁，重试，最多 3 次、间隔逐次加长。
- 实际执行：新加的并发测试在修复前**稳定复现**（5 轮 × 40 个客户：死锁 164 次）；修复后 0 次。按 C57 在本机连续跑 20 次，全部通过（第 4 节）。

## 2. 证据：InnoDB 记录的死锁（修复前）
```
*** (1) TRANSACTION: INSERT IGNORE INTO wallet_address (…)
*** (1) HOLDS THE LOCK(S): index uk_wallet_address_active … lock_mode X locks gap before rec
*** (1) WAITING FOR THIS LOCK TO BE GRANTED: index uk_wallet_address_active … lock_mode X locks gap before rec insert intention waiting
*** (2) TRANSACTION: INSERT IGNORE INTO wallet_address (…)
*** (2) HOLDS THE LOCK(S): index uk_wallet_address_active … lock_mode X locks gap before rec
*** (2) WAITING FOR THIS LOCK TO BE GRANTED: index uk_wallet_address_active … lock_mode X locks gap before rec insert intention waiting
*** WE ROLL BACK TRANSACTION (2)
```
（`SHOW ENGINE INNODB STATUS` 的 LATEST DETECTED DEADLOCK，2026-10-25 本机 MySQL 8.4.11。）

修完第 1 步以后，"同一客户几个请求同时到"还剩一种死锁：两个事务对同一个键 `INSERT IGNORE` 都撞上唯一键，InnoDB 在主键末尾（supremum）各留一个 X 锁，再插下一个体系时互相等待（日志里是 `index PRIMARY … supremum … insert intention waiting`）。不同客户之间不会出现，第 3 步用账户行锁让同一客户排队后也没有了。

## 3. 改了什么
| 文件 | 改法 |
|---|---|
| `backend/src/main/java/com/forecast/app/wallet/address/AddressService.java` | `allocateAddresses` 普通读 + `INSERT IGNORE`；`useForwarder` 新客户直接插入、只对已存在的旧地址加排他锁；`addressOf` 去掉大事务、分配单独一个事务并先锁账户行、被判死锁时有上限地重试 |
| `backend/src/main/java/com/forecast/app/wallet/address/AddressMapper.java`、`backend/src/main/resources/mapper/wallet/AddressMapper.xml` | 加 `findActive`（普通读）、`shareActive`（`FOR SHARE`，读最新提交的那一行）；`lockActive` 的注释写明"只在确定这一行存在时用" |
| `backend/src/test/java/com/forecast/app/wallet/address/AddressConcurrencyTest.java`（新） | xpub 方案：40 个新客户同时分配、40 个人同时"注册"（同一事务里先建用户再分配）、10 个客户各 4 个请求同时打开充值页，各跑 5 轮；断言 InnoDB 死锁计数（`lock_deadlocks`）不变、没有异常、每人每个体系恰好一个正在用的地址且是按序号派生的那个、同一客户的几个请求拿到同一个地址 |
| `backend/src/test/java/com/forecast/app/wallet/sweep/AddressForwarderConcurrencyTest.java`（新） | 合约地址方案（复用归集测试的上下文）：40 个新客户同时分配 5 轮；有旧 xpub 地址的 10 个客户各 4 个请求同时打开充值页：旧地址只停用一次、新合约地址只有一个 |

没有改表结构，没有迁移脚本。注册（`AuthService.register`）照旧在注册事务里调 `allocateAddresses`：新用户还没提交，别的请求看不到它，不会有同一客户的并发。

## 4. 验证（全部实际执行，2026-10-25）
| 检查 | 结果 |
|---|---|
| 修复前跑 `AddressConcurrencyTest` | 3 个用例全部失败：同时分配、同时注册各死锁约 160 次（`DeadlockLoserDataAccessException`），同时取地址另有 13 次 `NoSuchElementException` |
| 修复后，`AddressConcurrencyTest` + `AddressForwarderConcurrencyTest` 在本机**连续跑 20 次**（`mvn -o -B -ntp test -Dtest=…`，真实 MySQL 8.4 + Redis 7） | 20 次全部通过，每次 4 个打印"死锁 0 次" |
| 后端全部测试：`mvn -o -B -ntp verify`（`forecast_test` 删掉重建的空库，从 V1 迁移到 V12；本步新建的 MySQL 8.4.11 和后端测试专用的 Redis 7 容器） | 399 个（S4 的 394 个 + 本次 5 个），0 失败、0 错误、2 个跳过（同 S1～S4：真实节点、anvil 没设环境变量），BUILD SUCCESS，2 分 24 秒 |

## 5. 遗留
- 预压测脚本 `e2e/load/bet-load.mjs` 在 S4 改成了串行取地址，没有改回来（串行只影响准备阶段的速度；S12 正式压测时可以改回并发，顺便再验证一次）。
- `useForwarder` 里"以前停用过同一套参数、现在又换回来"的极少数情况仍会对不存在的行加锁读（参数换回去只在运维改配置后发生，`migrateAll` 是串行的），没有改。

## 6. 跨角色反馈 / 分歧记录
| 编号 | 提出方 → 接收方 | 内容 | 状态 |
|---|---|---|---|
| F-9 | 研发 → 测试 | 并发测试的入口见第 3 节；判定"没有死锁"看 InnoDB 的 `lock_deadlocks` 计数，不只看有没有 500 | 已采纳（流程说明） |

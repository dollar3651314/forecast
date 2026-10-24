#!/usr/bin/env bash
# 资金归集本地链端到端测试（技术方案/v2-半自动归集.md 第 4.11 节）：
#   anvil 本地链 → 用 Deploy.s.sol 部署 BitGo 工厂、实现合约、批量合约（和 Amos 部署主网用的是同一个脚本）→ 部署测试 USDT（不返回 bool）
#   → 后端 SweepAnvilTest 用真实 JSON-RPC 跑：CREATE2 地址 → 转 USDT → 扫块入账 → 归集 → 冷钱包收到 → parent 直接 flush → 撤销发起账户。
# 需要：Foundry（forge、anvil）、JDK 25 + Maven、MySQL（FC_DB_URL / FC_DB_USERNAME / FC_DB_PASSWORD，库会被清空重建）、Redis。
# 不需要任何私钥：部署账户和 parent 用 anvil 自带的已解锁账户（--unlocked），发起账户由测试随机生成。
#   用法：bash contracts/script/anvil-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(cd .. && pwd)
PORT=${ANVIL_PORT:-18545}
RPC=http://127.0.0.1:$PORT
LOG_DIR=${ANVIL_LOG_DIR:-$ROOT/e2e/logs}
mkdir -p "$LOG_DIR"

anvil --port "$PORT" --silent > "$LOG_DIR/anvil.log" 2>&1 &
ANVIL_PID=$!
trap 'kill $ANVIL_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  curl -s -X POST -H 'Content-Type: application/json' --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' "$RPC" > /dev/null 2>&1 && break
  sleep 0.2
done

# anvil 自带的前三个账户（公开的测试账户，不是任何环境的密钥）：0 号部署、1 号当冷钱包 parent、2 号当部署时登记的发起账户
DEPLOYER=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
PARENT=0x70997970C51812dc3A010C7d01b50e0d17dc79C8
OPERATOR=0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC

forge build -q
SWEEP_PARENT=$PARENT SWEEP_OPERATOR=$OPERATOR SWEEP_OWNER=$DEPLOYER \
  forge script script/Deploy.s.sol --rpc-url "$RPC" --sender "$DEPLOYER" --unlocked --broadcast -q > "$LOG_DIR/anvil-deploy.log" 2>&1 || {
  cat "$LOG_DIR/anvil-deploy.log"
  exit 1
}
forge script script/DeployTestToken.s.sol --rpc-url "$RPC" --sender "$DEPLOYER" --unlocked --broadcast -q >> "$LOG_DIR/anvil-deploy.log" 2>&1

addr() {
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print([t['contractAddress'] for t in d['transactions'] if t.get('contractName')==sys.argv[2] and t['transactionType']=='CREATE'][0])" "$1" "$2"
}
DEPLOY_JSON=broadcast/Deploy.s.sol/31337/run-latest.json
TOKEN_JSON=broadcast/DeployTestToken.s.sol/31337/run-latest.json
export FC_SWEEP_ANVIL_RPC=$RPC
export FC_SWEEP_ANVIL_IMPLEMENTATION=$(addr $DEPLOY_JSON ForwarderV4)
export FC_SWEEP_ANVIL_FACTORY=$(addr $DEPLOY_JSON ForwarderFactoryV4)
export FC_SWEEP_ANVIL_BATCHER=$(addr $DEPLOY_JSON SweepBatcher)
export FC_SWEEP_ANVIL_USDT=$(addr $TOKEN_JSON MockUsdtNoReturn)
export FC_SWEEP_ANVIL_PARENT=$PARENT
export FC_SWEEP_ANVIL_DEPLOYER=$DEPLOYER
echo "实现合约 $FC_SWEEP_ANVIL_IMPLEMENTATION，工厂 $FC_SWEEP_ANVIL_FACTORY，批量合约 $FC_SWEEP_ANVIL_BATCHER，测试 USDT $FC_SWEEP_ANVIL_USDT"

# 部署后核对（和 Amos 在主网上要跑的同一个脚本）
SWEEP_FACTORY=$FC_SWEEP_ANVIL_FACTORY SWEEP_IMPLEMENTATION=$FC_SWEEP_ANVIL_IMPLEMENTATION SWEEP_BATCHER=$FC_SWEEP_ANVIL_BATCHER \
  SWEEP_PARENT=$PARENT SWEEP_OPERATOR=$OPERATOR SWEEP_OWNER=$DEPLOYER \
  forge script script/VerifyDeployment.s.sol --rpc-url "$RPC" > "$LOG_DIR/anvil-verify.log" 2>&1 || {
  cat "$LOG_DIR/anvil-verify.log"
  exit 1
}
grep -q "全部通过" "$LOG_DIR/anvil-verify.log"
echo "部署后核对：全部通过"

cd "$ROOT"
mvn -B -ntp -f backend test -Dtest=SweepAnvilTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOG_DIR/anvil-e2e-mvn.log" | grep -E "anvil-e2e|Tests run|FAIL|ERROR\]" || true
grep -q "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0" "$LOG_DIR/anvil-e2e-mvn.log"
echo "anvil 端到端测试通过"

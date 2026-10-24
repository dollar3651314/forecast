package com.forecast.app.wallet.deposit;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.address.AddressService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的链替身（S19，只在 profile e2e 下存在）：三条链各一条内存里的"链"，启动时注册给扫块（{@link ChainClients}）。
 * 充值照常走真实的扫块 → 确认 → 入账（{@link DepositService#run}），只是区块和转账由测试制造。
 * <ul>
 *   <li>{@code POST /api/e2e/wallet/deposit {chain, address, amount}}：在下一个区块里向 address 转 amount 个 USDT（配置好的合约），扫一次块，返回交易哈希（此时是待确认）</li>
 *   <li>{@code POST /api/e2e/wallet/mine {chain, blocks}}：再出 blocks 个区块并扫块确认（够确认数就入账）</li>
 * </ul>
 */
@Component
@Profile(E2eGuard.PROFILE)
class E2eChain {

  record Deposit(String chain, String address, BigDecimal amount) {}

  record Mine(String chain, Integer blocks) {}

  /** 一条内存里的链：区块哈希固定（不回滚），转账只增不减 */
  static final class Ledger implements ChainClient {
    private long head = 1_000_000;
    private final List<TokenTransfer> transfers = new ArrayList<>();

    private static String hash(long block) {
      return String.format("0x%064x", block);
    }

    synchronized String transfer(String contract, String to, BigInteger raw) {
      long block = ++head;
      String tx = "0xe2e" + UUID.randomUUID().toString().replace("-", "");
      transfers.add(new TokenTransfer(tx, 0, block, hash(block), "0x00000000000000000000000000000000000e2e00", to, contract, raw));
      return tx;
    }

    synchronized void mine(int blocks) {
      head += blocks;
    }

    @Override
    public synchronized long headBlock() {
      return head;
    }

    @Override
    public synchronized List<TokenTransfer> transfers(long fromBlock, long toBlock, String contract) {
      return transfers.stream().filter(t -> t.blockNumber() >= fromBlock && t.blockNumber() <= toBlock && t.contract().equals(contract)).toList();
    }

    @Override
    public synchronized Optional<String> blockHash(long blockNumber) {
      return blockNumber <= head ? Optional.of(hash(blockNumber)) : Optional.empty();
    }
  }

  private final Map<Chain, Ledger> chains = new EnumMap<>(Chain.class);
  private final ChainClients clients;
  private final DepositService deposits;
  private final AddressService addresses;
  private final WalletProperties props;

  E2eChain(ChainClients clients, DepositService deposits, AddressService addresses, WalletProperties props) {
    this.clients = clients;
    this.deposits = deposits;
    this.addresses = addresses;
    this.props = props;
    for (Chain c : Chain.values()) chains.put(c, new Ledger());
  }

  @EventListener(ApplicationReadyEvent.class)
  void register() {
    chains.forEach(clients::register);
  }

  @RestController
  @Profile(E2eGuard.PROFILE)
  static class Api {

    private final E2eChain e2e;

    Api(E2eChain e2e) {
      this.e2e = e2e;
    }

    @PostMapping("/api/e2e/wallet/deposit")
    ResponseEntity<Map<String, Object>> deposit(@RequestBody Deposit d) {
      Chain chain = parse(d.chain());
      if (chain == null || d.address() == null || d.amount() == null || d.amount().signum() <= 0) return ResponseEntity.badRequest().build();
      WalletProperties.ChainConfig cfg = e2e.props.chain(chain);
      if (cfg == null || !cfg.scanReady()) return ResponseEntity.unprocessableEntity().build();
      Optional<Long> user = e2e.addresses.userOf(chain, d.address());
      if (user.isEmpty()) return ResponseEntity.notFound().build();
      BigInteger raw = d.amount().movePointRight(cfg.usdtDecimals()).toBigIntegerExact();
      String tx = e2e.chains.get(chain).transfer(cfg.usdtContract(), d.address(), raw);
      e2e.deposits.run(chain);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("txHash", tx);
      out.put("userId", String.valueOf(user.get()));
      out.put("status", status(e2e, user.get(), tx));
      return ResponseEntity.ok(out);
    }

    @PostMapping("/api/e2e/wallet/mine")
    ResponseEntity<Map<String, Object>> mine(@RequestBody Mine m) {
      Chain chain = parse(m.chain());
      if (chain == null || m.blocks() == null || m.blocks() < 1 || m.blocks() > 1000) return ResponseEntity.badRequest().build();
      e2e.chains.get(chain).mine(m.blocks());
      // 每次最多扫 scan-batch 个区块：多跑几次，扫到最新区块为止
      for (int i = 0; i < 1 + m.blocks() / 5; i++) e2e.deposits.run(chain);
      return ResponseEntity.ok(Map.of("head", e2e.chains.get(chain).headBlock()));
    }

    private static String status(E2eChain e2e, long userId, String tx) {
      return e2e.deposits.history(userId, 50).stream().filter(r -> r.txHash().equals(tx)).map(DepositService.DepositRecord::status).findFirst().orElse("NOT_FOUND");
    }

    private static Chain parse(String s) {
      try {
        return s == null ? null : Chain.valueOf(s);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
  }
}

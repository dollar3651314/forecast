package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.account.api.ShareCodeApi;
import com.forecast.app.growth.api.GrowthAdminApi;
import com.forecast.app.settle.SettleTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 分享链接的 ref 改用随机分享码（forecast S15，S14 的 SQ-36）：每个客户一个、随机、唯一、不变；来源统计照样按人归属（后台把分享码换成客户编号）；
 * 旧链接里 src=share 的纯数字 ref（客户编号）兼容。
 */
class S15ShareCodeTest extends SettleTestSupport {

  @Autowired ShareCodeApi shareCodes;
  @Autowired GrowthAdminApi growth;

  @Test
  void 分享码_随机_唯一_同一个客户不变_同时生成也只有一个() throws Exception {
    User a = user("0");
    List<CompletableFuture<String>> fs = new ArrayList<>();
    for (int i = 0; i < 6; i++) fs.add(CompletableFuture.supplyAsync(() -> shareCodes.codeOf(a.id())));
    Set<String> codes = new HashSet<>();
    for (CompletableFuture<String> f : fs) codes.add(f.get());
    assertThat(codes).hasSize(1);
    String code = codes.iterator().next();
    assertThat(ShareCodeApi.looksLikeCode(code)).isTrue();
    assertThat(ShareCodeApi.looksLikeCode(String.valueOf(a.id()))).as("纯数字的客户编号不是分享码").isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_share_code WHERE user_id = ?", Integer.class, a.id())).isEqualTo(1);
    User b = user("0");
    assertThat(shareCodes.codeOf(b.id())).isNotEqualTo(code);
    assertThat(shareCodes.owners(List.of(code, shareCodes.codeOf(b.id()), "zzzzzzzzzz", "123"))).containsOnly(Map.entry(code, a.id()),
        Map.entry(shareCodes.codeOf(b.id()), b.id()));
  }

  @Test
  void 来源统计_分享码换成客户编号_旧链接的数字ref兼容_渠道编号不当成客户() throws Exception {
    User sharer = user("0");
    String code = shareCodes.codeOf(sharer.id());
    String cmp = "c" + Long.toString(System.nanoTime(), 36);
    String dev = "dev-" + System.nanoTime() + "-bbbbbbbb";
    visit(dev + "1", "share", cmp, code);
    visit(dev + "2", "share", cmp, String.valueOf(sharer.id())); // S14 的旧链接
    visit(dev + "3", "tg", cmp, "4242"); // 渠道自己的编号
    GrowthAdminApi.Report r = growth.report(null, null, null);
    Map<String, String> byRef = new java.util.HashMap<>();
    for (GrowthAdminApi.Cohort c : r.cohorts()) if (cmp.equals(c.cmp())) byRef.put(c.src() + ":" + c.ref(), c.refCustomer());
    assertThat(byRef).containsEntry("share:" + code, String.valueOf(sharer.id())).containsEntry("share:" + sharer.id(), String.valueOf(sharer.id()))
        .containsEntry("tg:4242", null);
    String csv = growth.csv("sources", null, null, null, "en");
    assertThat(csv).contains("ref_customer_no").contains(cmp + "," + code + ",");
  }

  private void visit(String device, String src, String cmp, String ref) throws Exception {
    assertThat(call(post("/api/attribution/visit").header("X-Device-Id", device), null,
        "{\"src\":\"" + src + "\",\"cmp\":\"" + cmp + "\",\"ref\":\"" + ref + "\"}").getStatus()).isEqualTo(204);
  }
}

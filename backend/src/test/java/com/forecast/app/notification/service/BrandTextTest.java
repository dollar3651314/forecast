package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 旧品牌名检查（forecast S1，技术方案/v1-架构方案.md 第 1.4 节 ⑦）：后端发给客户的文字里没有旧品牌名。
 * <ul>
 *   <li>空库跑完全部迁移后，通知模板（标题和正文，中英文）里没有旧品牌名；</li>
 *   <li>后端代码里写死的字符串、{@code src/main/resources} 下的文件（配置、SQL、迁移脚本）里没有旧品牌名。</li>
 * </ul>
 * 检查哪些旧品牌名读 {@code scripts/brand-check.json}（和前端产物检查 {@code scripts/check-brand.mjs} 共用一份；
 * {@code enabled=false} 的不检查，见那个文件的说明）。迁移测试不启动 Spring，直接用 Flyway 在单独的库（测试库名加 {@code _brand_mig}）上执行，结束后删掉。
 * <p>复制源这个测试还检查"改品牌名的迁移不覆盖运营改过的模板"；forecast 是新库、基线里直接是新文字，没有这种迁移，那几个用例删除。
 */
class BrandTextTest {

  static final Path CONFIG = Path.of("../scripts/brand-check.json");

  static String url;
  static String dbName;
  static DriverManagerDataSource admin;
  static Pattern oldBrand;

  @BeforeAll
  static void setUp() throws IOException {
    JsonNode cfg = new ObjectMapper().readTree(Files.readString(CONFIG, StandardCharsets.UTF_8));
    List<String> enabled = new ArrayList<>();
    for (JsonNode b : cfg.get("oldBrands")) if (b.get("enabled").asBoolean()) enabled.add(b.get("pattern").asString().replace(" ?", "\\s*"));
    oldBrand = Pattern.compile("(?i)" + String.join("|", enabled));

    String base = env("FC_DB_URL", "jdbc:mysql://localhost:3306/forecast_test");
    Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^?]+)(\\?.*)?$").matcher(base);
    assertThat(m.matches()).as("FC_DB_URL 格式：jdbc:mysql://主机:端口/库名[?参数]").isTrue();
    dbName = m.group(2) + "_brand_mig";
    String params = m.group(3) == null ? "?" : m.group(3) + "&";
    url = m.group(1) + dbName + params.replace("createDatabaseIfNotExist=true&", "") + "createDatabaseIfNotExist=true";
    admin = new DriverManagerDataSource(url, env("FC_DB_USERNAME", "root"), System.getenv("FC_DB_PASSWORD"));
  }

  @AfterAll
  static void tearDown() {
    if (admin != null) new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS `" + dbName + "`");
  }

  static String env(String name, String def) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? def : v;
  }

  @Test
  void 配置里至少启用了一个旧品牌名_待定的品牌有开关() throws IOException {
    JsonNode cfg = new ObjectMapper().readTree(Files.readString(CONFIG, StandardCharsets.UTF_8));
    assertThat(oldBrand.pattern()).isNotEqualTo("(?i)");
    List<String> names = new ArrayList<>();
    for (JsonNode b : cfg.get("oldBrands")) {
      assertThat(b.has("enabled")).as("每一项都要写明 enabled").isTrue();
      names.add(b.get("name").asString());
    }
    assertThat(names).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void 空库_全部迁移后通知模板里没有旧品牌名() {
    JdbcTemplate jdbc = new JdbcTemplate(admin);
    // 删掉后下一次连接会自动重建空库（连接串带 createDatabaseIfNotExist=true）
    jdbc.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
    Flyway.configure().dataSource(admin).locations("classpath:db/migration").load().migrate();
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT code, title_zh, title_en, body_zh, body_en FROM notify_template");
    assertThat(rows).hasSizeGreaterThan(5);
    for (Map<String, Object> r : rows) {
      String text = r.get("title_zh") + "\n" + r.get("title_en") + "\n" + r.get("body_zh") + "\n" + r.get("body_en");
      assertThat(oldBrand.matcher(text).find()).as("模板 %s：%s", r.get("code"), text).isFalse();
    }
  }

  @Test
  void 后端代码里写死的字符串没有旧品牌名() throws IOException {
    Pattern literal = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    List<String> hits = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        Matcher m = literal.matcher(Files.readString(f, StandardCharsets.UTF_8));
        while (m.find()) if (oldBrand.matcher(m.group(1)).find()) hits.add(f + "：" + m.group());
      }
    }
    assertThat(hits).isEmpty();
  }

  @Test
  void 配置和SQL里没有旧品牌名() throws IOException {
    List<String> hits = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/resources"))) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        String text = Files.readString(f, StandardCharsets.UTF_8);
        Matcher m = oldBrand.matcher(text);
        if (m.find()) hits.add(f + "：…" + text.substring(Math.max(0, m.start() - 30), Math.min(text.length(), m.end() + 30)) + "…");
      }
    }
    assertThat(hits).isEmpty();
  }
}

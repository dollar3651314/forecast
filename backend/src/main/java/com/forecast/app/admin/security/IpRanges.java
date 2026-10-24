package com.forecast.app.admin.security;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** IP 白名单：逗号分隔的 IP 或网段（例如 {@code 203.0.113.0/24, 198.51.100.7}），支持 IPv4 和 IPv6。只接受字面 IP，不解析域名。 */
public final class IpRanges {

  private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

  record Range(byte[] network, int prefix) {}

  private final List<Range> ranges;

  private IpRanges(List<Range> ranges) {
    this.ranges = ranges;
  }

  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  /** 解析；有一项不合法就返回空 */
  public static Optional<IpRanges> parse(String text) {
    List<Range> out = new ArrayList<>();
    if (text == null || text.isBlank()) return Optional.of(new IpRanges(out));
    for (String part : text.split("[,，\\s]+")) {
      if (part.isBlank()) continue;
      String[] p = part.strip().split("/", -1);
      if (p.length > 2) return Optional.empty();
      Optional<byte[]> addr = literal(p[0]);
      if (addr.isEmpty()) return Optional.empty();
      int bits = addr.get().length * 8;
      int prefix = bits;
      if (p.length == 2) {
        try {
          prefix = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
          return Optional.empty();
        }
        if (prefix < 0 || prefix > bits) return Optional.empty();
      }
      out.add(new Range(addr.get(), prefix));
    }
    return Optional.of(new IpRanges(out));
  }

  public boolean contains(String ip) {
    Optional<byte[]> a = literal(ip);
    if (a.isEmpty()) return false;
    for (Range r : ranges) {
      if (r.network.length != a.get().length) continue;
      int bits = r.network.length * 8;
      BigInteger mask = r.prefix == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE).shiftRight(bits - r.prefix).shiftLeft(bits - r.prefix);
      if (new BigInteger(1, r.network).and(mask).equals(new BigInteger(1, a.get()).and(mask))) return true;
    }
    return false;
  }

  /** 只接受字面 IP（不做 DNS 查询）；IPv4 映射的 IPv6 地址按 IPv4 处理 */
  private static Optional<byte[]> literal(String s) {
    if (s == null || s.isBlank() || !LITERAL.matcher(s.strip()).matches()) return Optional.empty();
    String v = s.strip();
    if (v.indexOf(':') < 0 && v.chars().filter(c -> c == '.').count() != 3) return Optional.empty();
    try {
      byte[] b = InetAddress.getByName(v).getAddress();
      return Optional.of(b);
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }
}

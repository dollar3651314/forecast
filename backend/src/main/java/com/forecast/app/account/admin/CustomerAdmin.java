package com.forecast.app.account.admin;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.account.auth.AuthAccess;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.storage.ObjectStorage;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link CustomerAdminApi} 的实现：后台查看客户和 KYC。 */
@Service
class CustomerAdmin implements CustomerAdminApi {

  private final CustomerAdminMapper mapper;
  private final AuthAccess auth;
  private final ObjectStorage storage;
  private final Clock clock;

  CustomerAdmin(CustomerAdminMapper mapper, AuthAccess auth, ObjectStorage storage, Clock clock) {
    this.mapper = mapper;
    this.auth = auth;
    this.storage = storage;
    this.clock = clock;
  }

  static String name(String first, String middle, String last) {
    StringBuilder sb = new StringBuilder();
    for (String s : new String[] {first, middle, last}) {
      if (s == null || s.isBlank()) continue;
      if (!sb.isEmpty()) sb.append(' ');
      sb.append(s.strip());
    }
    return sb.toString();
  }

  private static Customer customer(CustomerAdminMapper.CustomerRow r) {
    return new Customer(r.id, r.email, name(r.firstName, r.middleName, r.lastName), r.nationality, r.status, r.kycStatus, r.createdAt, r.lastLoginAt, r.staff);
  }

  private static Long idOf(String keyword) {
    if (keyword == null) return null;
    try {
      return Long.parseLong(keyword);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Customer> customers(String keyword, String status, String kycStatus, int offset, int limit) {
    String kw = blank(keyword);
    Long id = idOf(kw);
    return new Page<>(mapper.customers(kw, id, blank(status), blank(kycStatus), offset, limit).stream().map(CustomerAdmin::customer).toList(),
        mapper.countCustomers(kw, id, blank(status), blank(kycStatus)));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CustomerDetail> customer(long id) {
    List<CustomerAdminMapper.CustomerRow> rows = mapper.byIds(List.of(id));
    if (rows.isEmpty()) return Optional.empty();
    CustomerAdminMapper.CustomerRow r = rows.getFirst();
    CustomerAdminMapper.LastLogin last = mapper.lastLogin(id);
    return Optional.of(new CustomerDetail(customer(r), r.firstName, r.middleName, r.lastName, r.birthDate, r.frozenReason, r.docType, r.docNumber,
        r.kycReviewedAt, r.kycRejectReason, last == null ? null : last.ip, last == null ? null : last.createdAt));
  }

  /** 姓名允许的字符：字母（含中文）、组合符号、撇号、空格、点、连字符；和客户端 validators.ts 的 NAME_RE 一致 */
  static final Pattern NAME_CHARS = Pattern.compile("[\\p{L}\\p{M}' .-]+");
  /** 姓名每段最多 50 字（客户端 validateName） */
  static final int NAME_MAX = 50;

  private static String name(ValidationException.Collector c, String field, String v, boolean required) {
    String s = v == null ? "" : v.strip();
    if (s.isEmpty()) {
      if (required) c.add(field, "required");
      return "";
    }
    if (s.length() > NAME_MAX) c.add(field, "nameTooLong");
    if (!NAME_CHARS.matcher(s).matches()) c.add(field, "nameChars");
    return s;
  }

  @Override
  @Transactional
  public ProfileEdit updateProfile(long userId, ProfileEdit e) {
    List<CustomerAdminMapper.CustomerRow> rows = mapper.byIds(List.of(userId));
    if (rows.isEmpty()) throw new BizException("NOT_FOUND", "客户不存在");
    CustomerAdminMapper.CustomerRow r = rows.getFirst();
    ProfileEdit before = new ProfileEdit(r.firstName, r.middleName, r.lastName, r.birthDate == null ? null : r.birthDate.toString(), r.nationality);
    ProfileEdit in = e == null ? new ProfileEdit(null, null, null, null, null) : e;
    ValidationException.Collector c = new ValidationException.Collector();
    String first = name(c, "firstName", in.firstName(), true);
    String middle = name(c, "middleName", in.middleName(), false);
    String last = name(c, "lastName", in.lastName(), true);
    // 出生日期：真实日期、不早于 1900 年、年满 18 周岁（和客户端 validateBirthDate 一致，按 UTC 日期算）
    LocalDate birth = null;
    LocalDate today = LocalDate.now(clock);
    String birthText = in.birthDate() == null ? "" : in.birthDate().strip();
    if (birthText.isEmpty()) c.add("birthDate", "required");
    else if (!birthText.matches("\\d{4}-\\d{2}-\\d{2}")) c.add("birthDate", "dateFormat");
    else {
      try {
        birth = LocalDate.parse(birthText);
      } catch (java.time.DateTimeException ex) {
        c.add("birthDate", "dateInvalid");
      }
    }
    if (birth != null && (birth.getYear() < 1900 || birth.isAfter(today))) c.add("birthDate", "dateInvalid");
    else if (birth != null && birth.plusYears(18).isAfter(today)) c.add("birthDate", "ageUnder18");
    String nat = in.nationality() == null ? "" : in.nationality().strip();
    if (nat.isEmpty()) c.add("nationality", "required");
    else if (!nat.matches("[A-Z]{2}")) c.add("nationality", "nationalityInvalid");
    c.throwIfAny();
    mapper.upsertProfile(userId, first, middle, last, birth, nat);
    return before;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, Customer> customers(Collection<Long> ids) {
    Map<Long, Customer> out = new LinkedHashMap<>();
    if (ids == null || ids.isEmpty()) return out;
    for (CustomerAdminMapper.CustomerRow r : mapper.byIds(ids.stream().distinct().toList())) out.put(r.id, customer(r));
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> search(String keyword, int limit) {
    String kw = blank(keyword);
    if (kw == null) return List.of();
    return mapper.customers(kw, idOf(kw), null, null, 0, limit).stream().map(r -> r.id).toList();
  }

  @Override
  @Transactional
  public boolean setStaff(long userId, boolean staff) {
    Boolean before = mapper.lockStaff(userId);
    if (before == null) throw new BizException("NOT_FOUND", "客户不存在");
    if (before != staff) mapper.setStaff(userId, staff);
    return before;
  }

  @Override
  @Transactional
  public void setFrozen(long userId, boolean frozen, String reason) {
    if (frozen) {
      auth.freeze(userId);
      mapper.setFrozenReason(userId, reason);
    } else {
      auth.unfreeze(userId);
      mapper.setFrozenReason(userId, null);
    }
  }

  private static KycApplication kyc(CustomerAdminMapper.KycRow r) {
    return new KycApplication(r.id, r.userId, name(r.firstName, r.middleName, r.lastName), r.email, r.nationality, r.birthDate, r.docType, r.docNumber,
        r.status, r.submittedAt, r.reviewerId, r.reviewedAt, r.rejectReason);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<KycApplication> kycApplications(String status, String keyword, int offset, int limit) {
    String kw = blank(keyword);
    Long id = idOf(kw);
    return new Page<>(mapper.kycs(blank(status), kw, id, offset, limit).stream().map(CustomerAdmin::kyc).toList(), mapper.countKycs(blank(status), kw, id));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<KycApplication> kycApplication(long id) {
    return Optional.ofNullable(mapper.kyc(id)).map(CustomerAdmin::kyc);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Image> kycImage(long applicationId, String side) {
    CustomerAdminMapper.KycRow r = mapper.kyc(applicationId);
    if (r == null) return Optional.empty();
    String key = switch (side == null ? "" : side) {
      case "front" -> r.frontKey;
      case "back" -> r.backKey;
      case "selfie" -> r.selfieKey;
      default -> throw new BizException("NOT_FOUND", "没有这张照片");
    };
    if (key == null || key.isBlank()) return Optional.empty();
    String type = key.endsWith(".png") ? "image/png" : "image/jpeg";
    byte[] content = storage.get(key);
    return content == null ? Optional.empty() : Optional.of(new Image(content, type));
  }

  @Override
  @Transactional(readOnly = true)
  public long pendingKyc() {
    return mapper.pendingKyc();
  }

  @Override
  @Transactional(readOnly = true)
  public long registeredSince(java.time.LocalDateTime since) {
    return mapper.registeredSince(since);
  }

  @Override
  @Transactional(readOnly = true)
  public long activeSince(java.time.LocalDateTime since) {
    return mapper.activeSince(since);
  }
}

package com.forecast.app.account.profile;

import com.forecast.app.account.api.KycStatus;
import com.forecast.app.account.kyc.KycAccess;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 个人资料：查看和修改。KYC 通过后锁定，不能再改（以证件为准）。 */
@RestController
class ProfileController {

  record ProfileView(String userId, String email, String firstName, String middleName, String lastName, LocalDate birthDate, String nationality,
      String registeredAt, boolean locked) {}

  record ProfileUpdate(
      @NotBlank(message = "REQUIRED") @Size(max = 64, message = "TOO_LONG") String firstName,
      @Size(max = 64, message = "TOO_LONG") String middleName,
      @NotBlank(message = "REQUIRED") @Size(max = 64, message = "TOO_LONG") String lastName,
      @NotNull(message = "REQUIRED") @Past(message = "BIRTH_DATE_INVALID") LocalDate birthDate,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "[A-Z]{2}", message = "NATIONALITY_INVALID") String nationality) {}

  private final ProfileMapper mapper;
  private final KycAccess kyc;

  ProfileController(ProfileMapper mapper, KycAccess kyc) {
    this.mapper = mapper;
    this.kyc = kyc;
  }

  @GetMapping("/api/me/profile")
  ProfileView get() {
    long id = CurrentUser.id();
    ProfileRow p = mapper.find(id);
    if (p == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return new ProfileView(String.valueOf(p.userId()), p.email(), p.firstName(), p.middleName(), p.lastName(), p.birthDate(), p.nationality(), utc(p.registeredAt()),
        kyc.status(id) == KycStatus.APPROVED);
  }

  @PutMapping("/api/me/profile")
  @Transactional
  ProfileView update(@Valid @RequestBody ProfileUpdate u) {
    long id = CurrentUser.id();
    if (kyc.status(id) == KycStatus.APPROVED) throw new BizException("PROFILE_LOCKED", "身份认证通过后不能修改资料");
    mapper.update(id, u.firstName().trim(), u.middleName() == null ? "" : u.middleName().trim(), u.lastName().trim(), u.birthDate(), u.nationality());
    return get();
  }

  /** 时间统一输出带时区的 UTC（例如 2026-10-14T10:00:00Z），和其他接口一致 */
  static String utc(LocalDateTime t) {
    return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC).toString();
  }
}

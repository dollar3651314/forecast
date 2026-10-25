package com.forecast.app.account.kyc;

import com.forecast.app.common.web.ApiError;
import com.forecast.app.common.web.CurrentUser;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 客户端的身份认证接口。审核在后台（S16），通过 AccountApi 调用。 */
@RestController
class KycController {

  /** 返回给客户的 KYC 信息。证件号只显示后 4 位；不返回图片和存储路径。 */
  record KycView(String status, String docType, String docNumber, String submittedAt, String reviewedAt, String rejectReason) {}

  private final KycService kyc;

  KycController(KycService kyc) {
    this.kyc = kyc;
  }

  static String mask(String n) {
    return n.length() <= 4 ? "****" : "****" + n.substring(n.length() - 4);
  }

  @GetMapping("/api/me/kyc")
  KycView get() {
    KycRow r = kyc.latest(CurrentUser.id());
    if (r == null) return new KycView("none", null, null, null, null, null);
    return new KycView(r.status().toLowerCase(), "ID_CARD".equals(r.docType()) ? "idCard" : "passport", mask(r.docNumber()), utc(r.submittedAt()), utc(r.reviewedAt()),
        "REJECTED".equals(r.status()) ? r.rejectReason() : null);
  }

  @PostMapping(value = "/api/me/kyc", consumes = "multipart/form-data")
  KycView submit(@RequestParam(required = false) String docType, @RequestParam(required = false) String docNumber,
      @RequestParam(required = false) MultipartFile front, @RequestParam(required = false) MultipartFile back,
      @RequestParam(required = false) MultipartFile selfie) throws IOException {
    kyc.submit(CurrentUser.id(), docType, docNumber, List.of(img("front", front), img("back", back), img("selfie", selfie)));
    return get();
  }

  private static KycService.Image img(String field, MultipartFile f) throws IOException {
    return new KycService.Image(field, f == null || f.isEmpty() ? null : f.getBytes());
  }

  @ExceptionHandler(KycService.KycValidationException.class)
  ResponseEntity<ApiError> invalid(KycService.KycValidationException e) {
    var fields = e.errors.stream().map(s -> s.split(":", 2)).map(p -> new ApiError.FieldError(p[0], p[1])).toList();
    return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "请求字段不合法", fields));
  }

  /** 时间统一输出带时区的 UTC，和其他接口一致 */
  static String utc(LocalDateTime t) {
    return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC).toString();
  }
}

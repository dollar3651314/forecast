package com.forecast.app.growth.waitlist;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 等候页的公开接口（forecast S16，MF-8；不用登录，{@code SessionFilter.PUBLIC}）。写请求照样只接受本站页面发起（约定 14），按 IP 限流（约定 16）。
 * <ul>
 *   <li>{@code POST /api/public/waitlist} {"email","lang","website"（隐藏的防刷字段，正常为空）,"attribution":{"src","cmp","ref","start"}}
 *       → 总是 {"status":"check_email"}（不暴露邮箱是否留过）；已经上线 409 WAITLIST_CLOSED；太频繁 429。</li>
 *   <li>{@code POST /api/public/waitlist/confirm} {"token"} → {"status":"confirmed"|"unsubscribed","lang"}；链接无效 404、过期 410。</li>
 *   <li>{@code POST /api/public/waitlist/unsubscribe} {"token"} → {"status":"unsubscribed","lang"}；链接无效 404。</li>
 * </ul>
 */
@RestController
class WaitlistController {

  record Source(String src, String cmp, String ref, String start) {}

  record SubscribeBody(@NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") @Size(max = 254, message = "EMAIL_INVALID") String email,
      @Size(max = 16) String lang, @Size(max = 200) String website, Source attribution) {}

  record TokenBody(String token) {}

  private final WaitlistService waitlist;

  WaitlistController(WaitlistService waitlist) {
    this.waitlist = waitlist;
  }

  @PostMapping("/api/public/waitlist")
  Map<String, String> subscribe(@Valid @RequestBody SubscribeBody b, HttpServletRequest req) {
    Source a = b.attribution() == null ? new Source(null, null, null, null) : b.attribution();
    waitlist.subscribe(new WaitlistService.Subscribe(b.email(), b.lang(), b.website(), a.src(), a.cmp(), a.ref(), a.start()), req.getRemoteAddr());
    return Map.of("status", "check_email");
  }

  @PostMapping("/api/public/waitlist/confirm")
  WaitlistService.LinkResult confirm(@RequestBody(required = false) TokenBody b) {
    return waitlist.confirm(b == null ? null : b.token());
  }

  @PostMapping("/api/public/waitlist/unsubscribe")
  WaitlistService.LinkResult unsubscribe(@RequestBody(required = false) TokenBody b) {
    return waitlist.unsubscribe(b == null ? null : b.token());
  }
}

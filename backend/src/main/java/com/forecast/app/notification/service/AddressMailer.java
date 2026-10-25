package com.forecast.app.notification.service;

import com.forecast.app.notification.api.MailApi;
import java.util.Map;
import org.springframework.stereotype.Service;

/** {@link MailApi} 的实现：按邮箱地址发（验证码、风控人员）。单独一个类，测试替换邮件时不影响站内信。 */
@Service
class AddressMailer implements MailApi {

  private final NotifyService service;

  AddressMailer(NotifyService service) {
    this.service = service;
  }

  @Override
  public void send(String to, String template, Map<String, String> params) {
    service.sendToAddress(to, template, params, null);
  }

  @Override
  public void send(String to, String template, Map<String, String> params, String lang) {
    service.sendToAddress(to, template, params, lang == null ? null : Render.lang(lang));
  }
}

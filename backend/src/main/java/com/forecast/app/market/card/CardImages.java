package com.forecast.app.market.card;

import com.forecast.app.market.api.CardImageApi;
import com.forecast.app.market.pub.PublicContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 卡片图片给内容工具用（forecast S15，MF-9，{@link CardImageApi}）：市场卡就是 S14 的分享卡片（{@link CardService#publicCard}）；
 * 数据卡、对照图用同一个画法（{@link CardPainter}），内容由调用方给。并发上限、缓存和分享卡片共用（{@link CardService#image}）。
 */
@Component
class CardImages implements CardImageApi {

  private final CardService cards;
  private final PublicContent content;

  CardImages(CardService cards, PublicContent content) {
    this.cards = cards;
    this.content = content;
  }

  @Override
  public Optional<Rendered> marketCard(long publicNo, String lang) {
    return cards.publicCard("market", publicNo, PublicContent.lang(lang)).map(m -> new Rendered(cards.image(m).png(), m.text()));
  }

  @Override
  public Rendered render(Spec s) {
    List<CardModel.Row> rows = new ArrayList<>();
    for (Row r : s.rows()) rows.add(new CardModel.Row(r.label(), r.pct(), r.ratio(), r.detail(), CardModel.Tone.PRIMARY, false));
    CardModel m = new CardModel("tool", PublicContent.lang(s.lang()), content.brand(), s.chip(), s.status(), s.headline(), s.title(), rows,
        s.facts() == null ? List.of() : s.facts(), s.notes() == null ? List.of() : s.notes(), s.stamp(), CardModel.Tone.PRIMARY);
    return new Rendered(cards.image(m).png(), m.text());
  }
}

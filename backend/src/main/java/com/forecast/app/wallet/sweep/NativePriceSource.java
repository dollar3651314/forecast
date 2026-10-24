package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.Chain;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 手续费币（ETH、BNB）的美元价格，归集预览用它估算手续费占比（超过"手续费占比上限"的地址跳过，提示 feeRatio）。
 * <p>复制源用自己行情模块的 ETHUSD、BNBUSD 报价；forecast 删除了行情模块（S1），默认实现 {@link NoNativePrice} 一律返回空：
 * 预览照常显示以币计的手续费，加提示 priceMissing，不做占比检查（归集由管理员看过预览后手动执行）。
 * 以后接上 S6 的 K 线数据源（币安、OKX 公开接口）时只换这个实现（遗留问题，见 开发记录/S1-复制骨架.md）。
 */
public interface NativePriceSource {

  /** 这条链手续费币的美元价格；取不到时为空。实现不能长时间阻塞（预览在后台请求里同步调用），外部调用要有超时（C81） */
  Optional<BigDecimal> usd(Chain chain);
}

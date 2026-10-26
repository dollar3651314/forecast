/**
 * 服务条款、隐私政策、风险披露的正文（forecast S11，PM 按需求说明书 v1.3.2 第 3、4、7、8 节起草；简体、英文、繁体三种）。
 * - 只写系统真实做到的：彩池玩法和结算公式、抽成只对盈利、截止和下注规则、裁决公示和争议、取消作废全额退回、补偿只补不扣、
 *   USDT 三条链充提、KYC、白名单、冷静期、大额延迟、人工审核。可以在后台调的数字写"默认"，以页面上写明的为准。
 * - 不写牌照、监管、公司主体、联系地址，这些位置写"待提供"；不写诱导收益的说法（市场策略 F-12、F-15：稳赚、必中、保本、翻倍等）。
 * - 三种语言的章节数、每节段落数一致，英文里不能有中文（legal.test.ts 检查）。品牌名用 BRAND_NAME（src/lib/brand.ts），不写死。
 * - 繁体是按简体人工转写的，上线前由懂繁体的人校对一遍（PQ-5）；正式使用前需要法务审核（页面顶部标着）。
 */
import type { Lang } from '../i18n/lang';
import { BRAND_NAME } from '../lib/brand';
import type { LegalPage } from '../lib/route';

export interface LegalSection {
  h: string;
  p: string[];
}

export interface LegalDoc {
  title: string;
  intro: string[];
  sections: LegalSection[];
}

/** 正文的更新日期 */
export const LEGAL_UPDATED = '2026-10-25';

const NUMBERS_ZH = '文中写"默认"的数字（费率、限额、门槛、手续费等）可能按市场或运营需要调整，以客户端页面上写明的为准。';
const NUMBERS_EN = 'Figures described as "default" (fees, limits, thresholds, network fees and so on) may be adjusted per market or by the operator; the figures shown in the client app apply.';
const NUMBERS_TW = '文中寫「預設」的數字（費率、限額、門檻、手續費等）可能按市場或營運需要調整，以客戶端頁面上寫明的為準。';

// =====================================================================
// 服务条款
// =====================================================================

const termsZh: LegalDoc = {
  title: '服务条款',
  intro: [
    `本服务条款适用于你对 ${BRAND_NAME} 网站、客户端及相关服务（以下统称"本服务"）的使用。注册即表示你已阅读并同意本条款、《隐私政策》和《风险披露》。`,
    NUMBERS_ZH,
    '本服务的运营主体、注册地、适用法律和争议解决方式：待提供。',
  ],
  sections: [
    {
      h: '1. 本服务是什么',
      p: [
        '本服务是用 USDT 参与的预测市场，玩法是彩池（pari-mutuel）：每个市场是一个有明确结果的问题，每个选项有自己的奖池；你把 USDT 押进你认为会发生的选项。',
        '平台不当对手方、不做市，输赢都在参与的人之间；平台只在结算时对押中者的盈利部分收取抽成。',
      ],
    },
    {
      h: '2. 账户',
      p: [
        '你需要年满 18 周岁，用本人的邮箱注册，一人一个账户，并对登录信息保密。账户里的操作视为你本人的操作。',
        '平台员工登记的账户不能下注；发现员工账户的下注会退回，不参与结算。',
      ],
    },
    {
      h: '3. 身份认证',
      p: [
        '累计充值超过认证门槛（默认 1,000 USDT）后，需要完成身份认证（证件和手持证件的自拍）才能继续新下注，已有的下注照常结算；提现前都需要完成身份认证。',
        '认证资料需要真实、属于你本人；审核结果会通知你，驳回时写明原因，可以修改后重新提交。',
      ],
    },
    {
      h: '4. 充值',
      p: [
        '只支持 USDT，网络可选 ERC20、TRC20、BSC（BEP20）。每个账户有自己的充值地址，链上确认后自动入账。最小充值 1 USDT，低于 1 USDT 的转账不会入账；转错网络、转错地址或转入其他代币无法找回。',
        '某个网络暂时没有开通时，客户端的充值页会写明，请不要向这个网络转账。',
      ],
    },
    {
      h: '5. 下注',
      p: [
        '每个市场写明问题、选项、判定条件、数据来源、开放和截止时间、费率。截止只看服务器收到下注请求的时间：收到时间早于截止时间才接受，正好等于截止时间的不接受。',
        '单笔下注默认最少 1 USDT、最多 5,000 USDT；同一个市场你的下注合计有上限（默认 10,000 USDT，加密货币短周期市场 2,000 USDT）。同一个市场可以押多个选项。下注后不能撤销。',
        '赔率随奖池变化，截止时确定最终赔率；下注时显示的预计回报只是按当时的奖池计算的估算。',
      ],
    },
    {
      h: '6. 结算和抽成',
      p: [
        '开奖后，输的选项的奖池按押中者的本金比例分给押中的人：你的盈利 = 你的本金 × 输方奖池 ÷ 押中选项的奖池（向下取整到 0.01 USDT）；抽成 = 盈利 × 费率（四舍五入到 0.01 USDT）；你拿回本金 + 盈利 − 抽成。分配后剩下的不足 0.01 USDT 的尾差归平台。',
        '抽成只对盈利收，本金不收，没押中不收。费率默认是盈利的 5%，每个市场单独写明；结算前修改费率会公开记录，在这个市场有下注的人会收到通知，结算按结算时的费率计算。',
        '每个市场结算后：派彩合计 + 抽成合计 + 尾差 = 奖池合计。派彩直接进入你的可用余额。',
      ],
    },
    {
      h: '7. 结果的判定、公示和争议',
      p: [
        '加密货币涨跌按两个数据来源（币安、OKX）的 K 线自动判定，两边一致才结算；体育比分在完赛后取两个比分来源，一致才自动结算。数据源不一致、拿不到或情况特殊时转由平台裁决。',
        '平台裁决的市场先公示拟定结果和依据，公示期（默认体育 2 小时、时事 24 小时）内，在这个市场有下注的人每人可以提交一次争议；公示期满没有争议就按拟定结果结算，有争议的处理后公开结论。',
        '已结算的市场公开结果、依据、裁决时间和裁决人编号（不公开员工姓名）。',
      ],
    },
    {
      h: '8. 取消、作废和退款',
      p: [
        '以下情况市场作废或取消，所有有效下注全额退回、不收任何费用：截止时只有一个选项有人押；没有人押中结果；比赛取消；比赛延期超过 48 小时仍没完赛；比赛中断后没有有效比分；规则无法判定结果；市场被平台取消。',
        '比赛提前开赛或截止时间设置有误时，晚于实际开赛时间收到的下注全额退回，其余照常结算。',
      ],
    },
    {
      h: '9. 判错后的补偿',
      p: ['已结算的市场如果事后发现结果判错，平台不追回已经派发的钱，由平台出资按正确结果补偿本应多得的人（补偿 = 按正确结果应得的金额 − 实际已得的金额），补偿直接进入可用余额并通知你。'],
    },
    {
      h: '10. 提现',
      p: [
        '只能提到你的提现白名单地址；新加的地址 24 小时后才能使用，白名单最多 10 个地址。',
        '单笔最小提现默认 100 USDT，并有单笔和每日上限；每条网络收取提现手续费（默认 ERC20 5 USDT、TRC20 1 USDT、BEP20 0.5 USDT），提交前会显示。',
        '每笔提现先有 2 小时冷静期，期间可以撤销；之后经审核发出，大额提现（默认 3,000 USDT 及以上）审核后再延迟 6 小时发出。只有确定交易没有上链时才会退回。',
      ],
    },
    {
      h: '11. 不允许的行为',
      p: [
        '不得用多个账户、他人身份或关联账户操纵结果或互相对押，不得利用系统错误、在已知结果后下注或以其他方式获取不正当利益；不得干扰本服务的正常运行。',
        '发现上述行为时，平台可以暂停账户、退回相关下注、拒绝或延迟相关提现，并保留记录。',
      ],
    },
    {
      h: '12. 服务变更和中断',
      p: ['平台可以调整市场、品类和参数，新规则只适用于调整后新开的市场。系统维护、网络或数据源故障可能导致暂停下注或延迟结算，期间资金不会丢失；已截止的市场照常按规则结算。'],
    },
    {
      h: '13. 通知和联系',
      p: ['重要事项通过站内信、推送和邮件通知你（可以在"通知偏好"里关闭推送和邮件，站内信始终保留）。联系邮箱：待提供。'],
    },
  ],
};

const termsEn: LegalDoc = {
  title: 'Terms of Service',
  intro: [
    `These Terms of Service apply to your use of the ${BRAND_NAME} website, client application and related services (together, the "Service"). By signing up you confirm that you have read and agree to these Terms, the Privacy Policy and the Risk Disclosure.`,
    NUMBERS_EN,
    'Operator, place of registration, governing law and dispute resolution: to be provided.',
  ],
  sections: [
    {
      h: '1. What the Service is',
      p: [
        'The Service is a prediction market where you take part with USDT. It works on a pari-mutuel (pool) basis: each market is a question with a clear outcome, each option has its own pool, and you put USDT into the option you expect to happen.',
        'The platform is never your counterparty and does not make markets; wins and losses are between participants. The platform only takes a fee on winners’ profit at settlement.',
      ],
    },
    {
      h: '2. Account',
      p: [
        'You must be at least 18, sign up with your own email address, hold only one account and keep your login details confidential. Actions taken in your account are treated as yours.',
        'Accounts registered as staff accounts cannot bet; bets found in a staff account are refunded and do not take part in settlement.',
      ],
    },
    {
      h: '3. Identity verification',
      p: [
        'Once your total deposits pass the verification threshold (1,000 USDT by default), you need to verify your identity (an ID document and a selfie holding it) before placing new bets; existing bets are settled as usual. Verification is required before any withdrawal.',
        'Verification documents must be genuine and your own. You will be notified of the outcome; a rejection states the reason, and you can correct and resubmit.',
      ],
    },
    {
      h: '4. Deposits',
      p: [
        'Only USDT is supported, on ERC20, TRC20 or BSC (BEP20). Each account has its own deposit addresses and deposits are credited automatically after on-chain confirmation. The minimum deposit is 1 USDT; smaller transfers are not credited. Transfers on the wrong network, to the wrong address or of other tokens cannot be recovered.',
        'If a network is not yet available, the deposit page in the client app says so; do not send funds on that network.',
      ],
    },
    {
      h: '5. Betting',
      p: [
        'Each market states the question, options, winning conditions, data sources, opening and closing times, and the fee. Only the time our server receives your bet counts: a bet is accepted only if it is received before the closing time; a bet received exactly at the closing time is rejected.',
        'By default a single bet is at least 1 USDT and at most 5,000 USDT, and your total in one market is capped (10,000 USDT by default, 2,000 USDT for short-term crypto markets). You may back several options in the same market. Bets cannot be cancelled.',
        'Odds move with the pool and the final odds are fixed at the close; the expected return shown when you bet is only an estimate based on the pool at that moment.',
      ],
    },
    {
      h: '6. Settlement and fees',
      p: [
        'When the result is in, the losing options’ pools are shared among the winners in proportion to their stakes: your profit = your stake × losing pool ÷ winning option’s pool (rounded down to 0.01 USDT); fee = profit × fee rate (rounded to 0.01 USDT); you receive stake + profit − fee. Any remainder below 0.01 USDT left after the split goes to the platform.',
        'The fee is charged only on profit: never on your stake, and nothing if you lose. The default fee is 5% of profit and each market states its own rate. Any change before settlement is recorded publicly and notified to everyone with a bet in that market, and settlement uses the rate in force at settlement.',
        'After each market is settled: total payouts + total fees + remainder = total pool. Payouts go straight to your available balance.',
      ],
    },
    {
      h: '7. Results, review period and disputes',
      p: [
        'Crypto up/down markets are resolved automatically from the candles of two data sources (Binance and OKX) and settle only if both agree; sports scores are taken from two score sources after the match and settle automatically only if they agree. If the sources disagree, are unavailable or the case is unusual, the platform resolves the market.',
        'For markets resolved by the platform, the proposed result and its basis are published first. During the review period (by default 2 hours for sports and 24 hours for news events), each person with a bet in that market may file one dispute. If there is no dispute by the end of the period, the market settles with the proposed result; disputes are decided and the conclusion is published.',
        'Settled markets show the result, its basis, the resolution time and the resolver’s ID number (staff names are not published).',
      ],
    },
    {
      h: '8. Cancellation, voiding and refunds',
      p: [
        'A market is voided or cancelled, and all valid bets are refunded in full with no fee, if: only one option has bets at the close; nobody backed the result; the event is cancelled; the event is postponed and not completed within 48 hours; play is abandoned without a valid score; the rules cannot determine a result; or the platform cancels the market.',
        'If an event starts early or a closing time was set incorrectly, bets received after the actual start are refunded in full and the rest are settled as usual.',
      ],
    },
    {
      h: '9. Compensation for wrong results',
      p: ['If a settled market is later found to have the wrong result, the platform does not claw back any payouts. Instead, the platform pays compensation to those who should have received more (compensation = amount due under the correct result − amount actually received). Compensation goes to your available balance and you will be notified.'],
    },
    {
      h: '10. Withdrawals',
      p: [
        'Withdrawals only go to addresses on your whitelist. A newly added address can be used after 24 hours, and the whitelist holds up to 10 addresses.',
        'The minimum withdrawal is 100 USDT by default, and per-transaction and daily limits apply. Each network has a withdrawal fee (by default ERC20 5 USDT, TRC20 1 USDT, BEP20 0.5 USDT), shown before you submit.',
        'Every withdrawal starts with a 2-hour cooling-off period during which you can cancel it. It is then reviewed and sent; large withdrawals (3,000 USDT or more by default) are sent 6 hours after approval. A withdrawal is returned only when it is certain that the transaction was not recorded on-chain.',
      ],
    },
    {
      h: '11. Prohibited conduct',
      p: [
        'You may not use multiple accounts, another person’s identity or linked accounts to manipulate results or bet against each other, exploit system errors, bet once the result is known or otherwise gain an unfair advantage, or interfere with the Service.',
        'Where we find such conduct, we may suspend the account, refund the bets involved, refuse or delay related withdrawals, and keep records.',
      ],
    },
    {
      h: '12. Changes and interruptions',
      p: ['We may change markets, categories and parameters; new rules apply only to markets opened after the change. Maintenance or network and data-source failures may pause betting or delay settlement; funds are not lost during such periods, and closed markets are settled under their rules.'],
    },
    {
      h: '13. Notices and contact',
      p: ['We notify you of important matters by in-app message, push notification and email (you can turn off push and email under Notification settings; in-app messages are always kept). Contact email: to be provided.'],
    },
  ],
};

const termsTw: LegalDoc = {
  title: '服務條款',
  intro: [
    `本服務條款適用於你對 ${BRAND_NAME} 網站、客戶端及相關服務（以下統稱「本服務」）的使用。註冊即表示你已閱讀並同意本條款、《隱私政策》和《風險揭露》。`,
    NUMBERS_TW,
    '本服務的營運主體、註冊地、適用法律和爭議解決方式：待提供。',
  ],
  sections: [
    {
      h: '1. 本服務是什麼',
      p: [
        '本服務是用 USDT 參與的預測市場，玩法是彩池（pari-mutuel）：每個市場是一個有明確結果的問題，每個選項有自己的獎池；你把 USDT 押進你認為會發生的選項。',
        '平台不當對手方、不做市，輸贏都在參與的人之間；平台只在結算時對押中者的盈利部分收取抽成。',
      ],
    },
    {
      h: '2. 帳戶',
      p: [
        '你需要年滿 18 週歲，用本人的郵箱註冊，一人一個帳戶，並對登入資訊保密。帳戶裡的操作視為你本人的操作。',
        '平台員工登記的帳戶不能下注；發現員工帳戶的下注會退回，不參與結算。',
      ],
    },
    {
      h: '3. 身分認證',
      p: [
        '累計充值超過認證門檻（預設 1,000 USDT）後，需要完成身分認證（證件和手持證件的自拍）才能繼續新下注，已有的下注照常結算；提現前都需要完成身分認證。',
        '認證資料需要真實、屬於你本人；審核結果會通知你，駁回時寫明原因，可以修改後重新提交。',
      ],
    },
    {
      h: '4. 充值',
      p: [
        '只支援 USDT，網路可選 ERC20、TRC20、BSC（BEP20）。每個帳戶有自己的充值地址，鏈上確認後自動入帳。最小充值 1 USDT，低於 1 USDT 的轉帳不會入帳；轉錯網路、轉錯地址或轉入其他代幣無法找回。',
        '某個網路暫時沒有開通時，客戶端的充值頁會寫明，請不要向這個網路轉帳。',
      ],
    },
    {
      h: '5. 下注',
      p: [
        '每個市場寫明問題、選項、判定條件、資料來源、開放和截止時間、費率。截止只看伺服器收到下注請求的時間：收到時間早於截止時間才接受，正好等於截止時間的不接受。',
        '單筆下注預設最少 1 USDT、最多 5,000 USDT；同一個市場你的下注合計有上限（預設 10,000 USDT，加密貨幣短週期市場 2,000 USDT）。同一個市場可以押多個選項。下注後不能撤銷。',
        '賠率隨獎池變化，截止時確定最終賠率；下注時顯示的預計回報只是按當時的獎池計算的估算。',
      ],
    },
    {
      h: '6. 結算和抽成',
      p: [
        '開獎後，輸的選項的獎池按押中者的本金比例分給押中的人：你的盈利 = 你的本金 × 輸方獎池 ÷ 押中選項的獎池（向下取整到 0.01 USDT）；抽成 = 盈利 × 費率（四捨五入到 0.01 USDT）；你拿回本金 + 盈利 − 抽成。分配後剩下的不足 0.01 USDT 的尾差歸平台。',
        '抽成只對盈利收，本金不收，沒押中不收。費率預設是盈利的 5%，每個市場單獨寫明；結算前修改費率會公開記錄，在這個市場有下注的人會收到通知，結算按結算時的費率計算。',
        '每個市場結算後：派彩合計 + 抽成合計 + 尾差 = 獎池合計。派彩直接進入你的可用餘額。',
      ],
    },
    {
      h: '7. 結果的判定、公示和爭議',
      p: [
        '加密貨幣漲跌按兩個資料來源（幣安、OKX）的 K 線自動判定，兩邊一致才結算；體育比分在完賽後取兩個比分來源，一致才自動結算。資料來源不一致、取不到或情況特殊時轉由平台裁決。',
        '平台裁決的市場先公示擬定結果和依據，公示期（預設體育 2 小時、時事 24 小時）內，在這個市場有下注的人每人可以提交一次爭議；公示期滿沒有爭議就按擬定結果結算，有爭議的處理後公開結論。',
        '已結算的市場公開結果、依據、裁決時間和裁決人編號（不公開員工姓名）。',
      ],
    },
    {
      h: '8. 取消、作廢和退款',
      p: [
        '以下情況市場作廢或取消，所有有效下注全額退回、不收任何費用：截止時只有一個選項有人押；沒有人押中結果；比賽取消；比賽延期超過 48 小時仍沒完賽；比賽中斷後沒有有效比分；規則無法判定結果；市場被平台取消。',
        '比賽提前開賽或截止時間設定有誤時，晚於實際開賽時間收到的下注全額退回，其餘照常結算。',
      ],
    },
    {
      h: '9. 判錯後的補償',
      p: ['已結算的市場如果事後發現結果判錯，平台不追回已經派發的錢，由平台出資按正確結果補償本應多得的人（補償 = 按正確結果應得的金額 − 實際已得的金額），補償直接進入可用餘額並通知你。'],
    },
    {
      h: '10. 提現',
      p: [
        '只能提到你的提現白名單地址；新加的地址 24 小時後才能使用，白名單最多 10 個地址。',
        '單筆最小提現預設 100 USDT，並有單筆和每日上限；每條網路收取提現手續費（預設 ERC20 5 USDT、TRC20 1 USDT、BEP20 0.5 USDT），提交前會顯示。',
        '每筆提現先有 2 小時冷靜期，期間可以撤銷；之後經審核發出，大額提現（預設 3,000 USDT 及以上）審核後再延遲 6 小時發出。只有確定交易沒有上鏈時才會退回。',
      ],
    },
    {
      h: '11. 不允許的行為',
      p: [
        '不得用多個帳戶、他人身分或關聯帳戶操縱結果或互相對押，不得利用系統錯誤、在已知結果後下注或以其他方式取得不正當利益；不得干擾本服務的正常運作。',
        '發現上述行為時，平台可以暫停帳戶、退回相關下注、拒絕或延遲相關提現，並保留記錄。',
      ],
    },
    {
      h: '12. 服務變更和中斷',
      p: ['平台可以調整市場、品類和參數，新規則只適用於調整後新開的市場。系統維護、網路或資料來源故障可能導致暫停下注或延遲結算，期間資金不會遺失；已截止的市場照常按規則結算。'],
    },
    {
      h: '13. 通知和聯絡',
      p: ['重要事項透過站內信、推播和郵件通知你（可以在「通知偏好」裡關閉推播和郵件，站內信始終保留）。聯絡郵箱：待提供。'],
    },
  ],
};

// =====================================================================
// 隐私政策
// =====================================================================

const privacyZh: LegalDoc = {
  title: '隐私政策',
  intro: [`本隐私政策说明 ${BRAND_NAME} 收集哪些个人信息、为什么收集、怎样使用、保存和保护。`, NUMBERS_ZH, '个人信息的处理者（运营主体）、联系地址和信息存放地点：待提供。'],
  sections: [
    {
      h: '1. 我们收集的信息',
      p: [
        '注册和账户信息：邮箱、密码（只保存不可还原的加密结果）、姓名、出生日期、国籍；勾选"已年满 18 周岁并同意服务条款"的时间。',
        '身份认证信息：证件类型和号码、证件照片、手持证件的自拍。',
        '资金和下注记录：充值、提现、提现白名单地址、下注、派彩、抽成、退款、补偿。',
        '使用信息：登录时间、IP 地址、浏览器生成的设备编号、通知偏好和界面语言；你订阅的开赛提醒。',
      ],
    },
    {
      h: '2. 我们怎样使用',
      p: [
        '提供本服务：开户、充值入账、下注和结算、提现、发送你需要的通知。',
        '保护资金和公平：身份认证、防止多账户和关联账户操纵结果、提现审核和风险控制；系统会把同设备、同 IP、同充提地址的账户关联起来，由员工复核。',
      ],
    },
    {
      h: '3. 我们不做的事',
      p: ['我们不出售你的个人信息，也不把它用于广告投放；网站和客户端不使用任何第三方统计、广告或追踪代码。不会主动给你发营销推送；只有你主动订阅的开赛提醒才会发邮件，邮件里可以随时退订。'],
    },
    {
      h: '4. 保存和保护',
      p: [
        '账户、资金和下注记录在账户存续期间和法律要求的期限内保存；数据保存在本服务的服务器上，传输加密，员工按权限访问，重要操作都有审计记录。',
        '密码只保存不可还原的加密结果，员工也看不到你的密码。',
      ],
    },
    {
      h: '5. 和第三方共享',
      p: ['只在提供服务必要时共享：发送邮件和推送的服务商只拿到收件地址和通知内容；区块链上的转账本身是公开的。法律要求时按要求提供。'],
    },
    {
      h: '6. 你的权利',
      p: ['你可以在客户端查看和更新个人资料（身份认证通过后姓名和出生日期按证件锁定），关闭推送和邮件通知，退订开赛提醒。需要查询、更正或删除个人信息时，可以联系我们：待提供。'],
    },
    {
      h: '7. 浏览器本地存储',
      p: ['客户端用浏览器本地存储记住你的语言、主题、是否看过新手引导和设备编号；登录状态保存在只有服务器能读的 Cookie 里，网页脚本读不到。我们不使用广告或追踪 Cookie。'],
    },
  ],
};

const privacyEn: LegalDoc = {
  title: 'Privacy Policy',
  intro: [`This Privacy Policy explains what personal information ${BRAND_NAME} collects, why, and how it is used, stored and protected.`, NUMBERS_EN, 'Data controller (operator), contact address and where data is stored: to be provided.'],
  sections: [
    {
      h: '1. Information we collect',
      p: [
        'Sign-up and account information: email, password (stored only as an irreversible hash), name, date of birth and nationality; the time you confirmed you are 18 or older and agreed to the Terms.',
        'Identity verification information: document type and number, document photos and a selfie holding the document.',
        'Funds and betting records: deposits, withdrawals, whitelisted withdrawal addresses, bets, payouts, fees, refunds and compensation.',
        'Usage information: login times, IP addresses, a device ID generated by your browser, notification settings and interface language, and the opening reminders you subscribe to.',
      ],
    },
    {
      h: '2. How we use it',
      p: [
        'To provide the Service: opening your account, crediting deposits, placing and settling bets, processing withdrawals and sending the notifications you need.',
        'To protect funds and fairness: identity verification, preventing multiple or linked accounts from manipulating results, withdrawal review and risk control. The system links accounts that share a device, IP address or deposit/withdrawal address, and staff review those links.',
      ],
    },
    {
      h: '3. What we do not do',
      p: ['We do not sell your personal information or use it for advertising, and our website and client do not use any third-party analytics, advertising or tracking code. We do not send you marketing pushes; only the opening reminders you subscribe to are emailed, and each email lets you unsubscribe.'],
    },
    {
      h: '4. Storage and protection',
      p: [
        'Account, funds and betting records are kept while your account exists and for as long as the law requires. Data is stored on the Service’s servers and encrypted in transit; staff access it according to their permissions, and important actions are audited.',
        'Passwords are stored only as irreversible hashes; staff cannot see your password.',
      ],
    },
    {
      h: '5. Sharing with third parties',
      p: ['We share information only where needed to provide the Service: email and push providers receive only the recipient address and the notification content, and blockchain transfers are public by nature. We provide information when the law requires it.'],
    },
    {
      h: '6. Your rights',
      p: ['In the client app you can view and update your profile (once verified, your name and date of birth are locked to your documents), turn off push and email notifications, and unsubscribe from opening reminders. To access, correct or delete your personal information, contact us: to be provided.'],
    },
    {
      h: '7. Browser storage',
      p: ['The client app uses your browser’s local storage to remember your language, theme, whether you have seen the getting-started guide, and the device ID. Your login is kept in a cookie that only our server can read; page scripts cannot access it. We do not use advertising or tracking cookies.'],
    },
  ],
};

const privacyTw: LegalDoc = {
  title: '隱私政策',
  intro: [`本隱私政策說明 ${BRAND_NAME} 蒐集哪些個人資訊、為什麼蒐集、怎樣使用、保存和保護。`, NUMBERS_TW, '個人資訊的處理者（營運主體）、聯絡地址和資訊存放地點：待提供。'],
  sections: [
    {
      h: '1. 我們蒐集的資訊',
      p: [
        '註冊和帳戶資訊：郵箱、密碼（只保存不可還原的加密結果）、姓名、出生日期、國籍；勾選「已年滿 18 週歲並同意服務條款」的時間。',
        '身分認證資訊：證件類型和號碼、證件照片、手持證件的自拍。',
        '資金和下注記錄：充值、提現、提現白名單地址、下注、派彩、抽成、退款、補償。',
        '使用資訊：登入時間、IP 位址、瀏覽器產生的裝置編號、通知偏好和介面語言；你訂閱的開賽提醒。',
      ],
    },
    {
      h: '2. 我們怎樣使用',
      p: [
        '提供本服務：開戶、充值入帳、下注和結算、提現、發送你需要的通知。',
        '保護資金和公平：身分認證、防止多帳戶和關聯帳戶操縱結果、提現審核和風險控制；系統會把同裝置、同 IP、同充提地址的帳戶關聯起來，由員工複核。',
      ],
    },
    {
      h: '3. 我們不做的事',
      p: ['我們不出售你的個人資訊，也不把它用於廣告投放；網站和客戶端不使用任何第三方統計、廣告或追蹤程式碼。不會主動給你發行銷推播；只有你主動訂閱的開賽提醒才會發郵件，郵件裡可以隨時退訂。'],
    },
    {
      h: '4. 保存和保護',
      p: [
        '帳戶、資金和下注記錄在帳戶存續期間和法律要求的期限內保存；資料保存在本服務的伺服器上，傳輸加密，員工按權限存取，重要操作都有稽核記錄。',
        '密碼只保存不可還原的加密結果，員工也看不到你的密碼。',
      ],
    },
    {
      h: '5. 和第三方共享',
      p: ['只在提供服務必要時共享：發送郵件和推播的服務商只拿到收件地址和通知內容；區塊鏈上的轉帳本身是公開的。法律要求時按要求提供。'],
    },
    {
      h: '6. 你的權利',
      p: ['你可以在客戶端查看和更新個人資料（身分認證通過後姓名和出生日期按證件鎖定），關閉推播和郵件通知，退訂開賽提醒。需要查詢、更正或刪除個人資訊時，可以聯絡我們：待提供。'],
    },
    {
      h: '7. 瀏覽器本機儲存',
      p: ['客戶端用瀏覽器本機儲存記住你的語言、主題、是否看過新手引導和裝置編號；登入狀態保存在只有伺服器能讀的 Cookie 裡，網頁腳本讀不到。我們不使用廣告或追蹤 Cookie。'],
    },
  ],
};

// =====================================================================
// 风险披露
// =====================================================================

const riskZh: LegalDoc = {
  title: '风险披露',
  intro: [`本风险披露说明参与 ${BRAND_NAME} 预测的主要风险，但不能列出全部风险。请在参与前读完。`, NUMBERS_ZH],
  sections: [
    {
      h: '1. 你可能损失全部本金',
      p: ['预测有输有赢：你押的选项输了，你在这个选项上的本金会全部损失。过去的结果不代表以后的结果。请只用你能承受损失的资金参与。'],
    },
    {
      h: '2. 赔率在截止前会变',
      p: ['彩池玩法的赔率取决于各选项奖池的比例，截止前随着别人下注不断变化；下注时显示的预计回报只是估算，最终回报按截止时的奖池和结算时的费率计算，可能比你下注时看到的低。'],
    },
    {
      h: '3. 押中也可能拿得少',
      p: ['押中的人越多、输方奖池越小，盈利越少；平台对盈利收取抽成。截止时只有一个选项有人押、或者没有人押中结果时，市场作废、全额退回，没有盈利。'],
    },
    {
      h: '4. 结果判定',
      p: ['结果按市场规则和写明的数据来源判定。数据来源出错、延迟、两个来源不一致或规则有歧义时，结算可能延后、转由平台裁决，或者市场作废退款。公示期内可以提交争议，但争议不一定被采纳。'],
    },
    {
      h: '5. 下注不能撤销',
      p: ['下注提交后不能撤销；请确认选项和金额后再提交。截止只看服务器收到下注的时间，网络延迟可能导致你的下注在截止后才到而被拒绝。'],
    },
    {
      h: '6. 区块链和资金',
      p: ['区块链上的转账一旦确认就不能撤回；转错网络、转错地址或转入其他代币无法找回。USDT 本身的价格、发行方和所在区块链的风险由你自行承担。提现要经过冷静期和审核，到账需要时间。'],
    },
    {
      h: '7. 系统和账户安全',
      p: ['系统维护、网络中断或第三方服务故障可能导致暂时无法下注、查看或提现。请保管好你的邮箱和密码；因你自己泄露登录信息造成的损失由你承担。'],
    },
    {
      h: '8. 法律和监管',
      p: ['你有责任确认使用本服务符合你所在国家或地区的法律。监管与牌照信息：待提供。本网站和客户端的内容不构成投资建议。'],
    },
  ],
};

const riskEn: LegalDoc = {
  title: 'Risk Disclosure',
  intro: [`This Risk Disclosure describes the main risks of making predictions on ${BRAND_NAME}, but it cannot list every risk. Please read it before you take part.`, NUMBERS_EN],
  sections: [
    {
      h: '1. You may lose your whole stake',
      p: ['Predictions can be lost: if the option you backed loses, your whole stake on that option is lost. Past results do not predict future results. Only use money you can afford to lose.'],
    },
    {
      h: '2. Odds change until the close',
      p: ['In a pool-based market the odds depend on the relative size of each option’s pool and keep changing as others bet until the close. The expected return shown when you bet is only an estimate; your final return is calculated from the pool at the close and the fee at settlement, and may be lower than what you saw when you bet.'],
    },
    {
      h: '3. Winning may pay less than expected',
      p: ['The more people back the winning option and the smaller the losing pool, the smaller the profit; the platform takes a fee on profit. If only one option has bets at the close, or nobody backed the result, the market is voided and refunded in full, with no profit.'],
    },
    {
      h: '4. How results are determined',
      p: ['Results are determined under each market’s rules and stated data sources. If a source is wrong or late, two sources disagree or the rules are ambiguous, settlement may be delayed, the platform may resolve the market, or the market may be voided and refunded. You can file a dispute during the review period, but it may not be upheld.'],
    },
    {
      h: '5. Bets cannot be cancelled',
      p: ['Once submitted, a bet cannot be cancelled, so check the option and amount first. Only the time our server receives your bet counts; network delays may cause a bet to arrive after the close and be rejected.'],
    },
    {
      h: '6. Blockchain and funds',
      p: ['A confirmed blockchain transfer cannot be reversed; transfers on the wrong network, to the wrong address or of other tokens cannot be recovered. You bear the risks of USDT itself, its issuer and the blockchains it runs on. Withdrawals go through a cooling-off period and review, so they take time to arrive.'],
    },
    {
      h: '7. Systems and account security',
      p: ['Maintenance, network outages or third-party failures may temporarily prevent betting, viewing or withdrawing. Keep your email and password safe; losses caused by you disclosing your login details are your responsibility.'],
    },
    {
      h: '8. Legal and regulatory',
      p: ['You are responsible for making sure that using the Service is lawful where you live. Regulatory and licence information: to be provided. Nothing on this website or in the client app is investment advice.'],
    },
  ],
};

const riskTw: LegalDoc = {
  title: '風險揭露',
  intro: [`本風險揭露說明參與 ${BRAND_NAME} 預測的主要風險，但不能列出全部風險。請在參與前讀完。`, NUMBERS_TW],
  sections: [
    {
      h: '1. 你可能損失全部本金',
      p: ['預測有輸有贏：你押的選項輸了，你在這個選項上的本金會全部損失。過去的結果不代表以後的結果。請只用你能承受損失的資金參與。'],
    },
    {
      h: '2. 賠率在截止前會變',
      p: ['彩池玩法的賠率取決於各選項獎池的比例，截止前隨著別人下注不斷變化；下注時顯示的預計回報只是估算，最終回報按截止時的獎池和結算時的費率計算，可能比你下注時看到的低。'],
    },
    {
      h: '3. 押中也可能拿得少',
      p: ['押中的人越多、輸方獎池越小，盈利越少；平台對盈利收取抽成。截止時只有一個選項有人押、或者沒有人押中結果時，市場作廢、全額退回，沒有盈利。'],
    },
    {
      h: '4. 結果判定',
      p: ['結果按市場規則和寫明的資料來源判定。資料來源出錯、延遲、兩個來源不一致或規則有歧義時，結算可能延後、轉由平台裁決，或者市場作廢退款。公示期內可以提交爭議，但爭議不一定被採納。'],
    },
    {
      h: '5. 下注不能撤銷',
      p: ['下注提交後不能撤銷；請確認選項和金額後再提交。截止只看伺服器收到下注的時間，網路延遲可能導致你的下注在截止後才到而被拒絕。'],
    },
    {
      h: '6. 區塊鏈和資金',
      p: ['區塊鏈上的轉帳一旦確認就不能撤回；轉錯網路、轉錯地址或轉入其他代幣無法找回。USDT 本身的價格、發行方和所在區塊鏈的風險由你自行承擔。提現要經過冷靜期和審核，到帳需要時間。'],
    },
    {
      h: '7. 系統和帳戶安全',
      p: ['系統維護、網路中斷或第三方服務故障可能導致暫時無法下注、查看或提現。請保管好你的郵箱和密碼；因你自己洩露登入資訊造成的損失由你承擔。'],
    },
    {
      h: '8. 法律和監管',
      p: ['你有責任確認使用本服務符合你所在國家或地區的法律。監管與牌照資訊：待提供。本網站和客戶端的內容不構成投資建議。'],
    },
  ],
};

export const LEGAL: Record<LegalPage, Record<Lang, LegalDoc>> = {
  terms: { 'zh-CN': termsZh, 'zh-TW': termsTw, en: termsEn },
  privacy: { 'zh-CN': privacyZh, 'zh-TW': privacyTw, en: privacyEn },
  risk: { 'zh-CN': riskZh, 'zh-TW': riskTw, en: riskEn },
};

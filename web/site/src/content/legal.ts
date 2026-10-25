/**
 * 服务条款、隐私政策、风险披露的占位正文。
 * - forecast S1：复制源的三份样板是为差价合约写的（服务内容、杠杆、强平、交易数据），整体删除，只保留法律页框架（架构方案第 3 节）。
 *   这里先放占位文字，写明"待 PM 按预测市场重写"；PM 写好正文后替换（C6），上线前需要法务审核。
 * - 只写系统真实做到的（USDT 充提、三条链、最小充值、KYC、白名单、冷静期、人工审核）；不写牌照、监管、公司主体，这些位置写"待提供"。
 * - 中英文的章节数必须一致，英文里不能有中文（legal.test.ts 检查）。品牌名用 BRAND_NAME（src/lib/brand.ts），不写死。
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

/** 占位文字的更新日期 */
export const LEGAL_UPDATED = '2026-10-24';

const DRAFT_ZH = '本文件正在按预测市场（彩池玩法）重新撰写，下面只列出已经确定的内容；完整文本发布前会经过法务审核。';
const DRAFT_EN = 'This document is being rewritten for the prediction market (pool-based) service. Only the parts that are already settled are listed below; the full text will be reviewed by legal counsel before it is published.';

const termsZh: LegalDoc = {
  title: '服务条款',
  intro: [`本服务条款适用于你对 ${BRAND_NAME} 网站、客户端及相关服务（以下统称"本服务"）的使用。`, DRAFT_ZH, '本服务的运营主体、注册地、适用法律和争议解决方式：待提供。'],
  sections: [
    {
      h: '1. 账户',
      p: ['你需要年满 18 周岁，用邮箱注册账户，并对账户的登录信息保密。', '提现前需要完成身份认证（KYC）。'],
    },
    {
      h: '2. 充值和提现',
      p: [
        '只支持 USDT，网络可选 ERC20、TRC20、BSC（BEP20）。最小充值 1 USDT，低于 1 USDT 的转账不会入账；转错网络或转入其他代币无法找回。',
        '只能提到白名单里的地址，新加的地址 24 小时后才能使用。每笔提现先有冷静期（期间可以撤销），再经人工审核；大额提现审核后还会延迟一段时间再发出。',
      ],
    },
    {
      h: '3. 预测玩法规则',
      p: ['待提供：玩法、结算、手续费和争议处理的规则由 PM 撰写后补充。'],
    },
  ],
};

const termsEn: LegalDoc = {
  title: 'Terms of Service',
  intro: [`These Terms of Service apply to your use of the ${BRAND_NAME} website, client application and related services (together, the "Service").`, DRAFT_EN, 'Operator, place of registration, governing law and dispute resolution: to be provided.'],
  sections: [
    {
      h: '1. Account',
      p: ['You must be at least 18 years old, sign up with your email address and keep your login details confidential.', 'You need to complete identity verification (KYC) before you can withdraw.'],
    },
    {
      h: '2. Deposits and withdrawals',
      p: [
        'Only USDT is supported, on ERC20, TRC20 or BSC (BEP20). The minimum deposit is 1 USDT; smaller transfers are not credited. Transfers on the wrong network or of other tokens cannot be recovered.',
        'Withdrawals only go to addresses on your whitelist, and a newly added address can be used after 24 hours. Every withdrawal starts with a cooling-off period (you can cancel during it) and is then reviewed by a person; large withdrawals are also held for a while after approval.',
      ],
    },
    {
      h: '3. Prediction rules',
      p: ['To be provided: the rules for predictions, settlement, fees and disputes will be added once they are written.'],
    },
  ],
};

const privacyZh: LegalDoc = {
  title: '隐私政策',
  intro: [`本隐私政策说明 ${BRAND_NAME} 收集哪些个人信息、怎样使用和保护。`, DRAFT_ZH, '个人信息的处理者（运营主体）、联系地址和信息存放地点：待提供。'],
  sections: [
    {
      h: '1. 我们收集的信息',
      p: ['注册和账户信息（邮箱、姓名、出生日期、国籍）、身份认证信息（证件和照片）、充值和提现记录、登录时间和 IP 地址。'],
    },
    {
      h: '2. 我们不做的事',
      p: ['我们不出售你的个人信息，也不把它用于广告投放；网站和客户端不使用任何第三方统计、广告或追踪代码。'],
    },
  ],
};

const privacyEn: LegalDoc = {
  title: 'Privacy Policy',
  intro: [`This Privacy Policy explains what personal information ${BRAND_NAME} collects and how it is used and protected.`, DRAFT_EN, 'Data controller (operator), contact address and where data is stored: to be provided.'],
  sections: [
    {
      h: '1. Information we collect',
      p: ['Sign-up and account information (email, name, date of birth, nationality), identity verification information (ID documents and photos), deposit and withdrawal records, and login times and IP addresses.'],
    },
    {
      h: '2. What we do not do',
      p: ['We do not sell your personal information or use it for advertising, and our website and client do not use any third-party analytics, advertising or tracking code.'],
    },
  ],
};

const riskZh: LegalDoc = {
  title: '风险披露',
  intro: [`本风险披露说明参与 ${BRAND_NAME} 预测的主要风险，但不能列出全部风险。`, DRAFT_ZH],
  sections: [
    {
      h: '1. 你可能损失全部投入',
      p: ['预测有输有赢：猜错时，你投入的金额会全部损失。请只用你能承受损失的资金参与。'],
    },
    {
      h: '2. 区块链转账',
      p: ['区块链上的转账一旦确认就不能撤回；转错网络、转错地址或转入其他代币无法找回。'],
    },
    {
      h: '3. 法律和监管',
      p: ['你有责任确认使用本服务符合你所在国家或地区的法律。监管与牌照信息：待提供。'],
    },
  ],
};

const riskEn: LegalDoc = {
  title: 'Risk Disclosure',
  intro: [`This Risk Disclosure describes the main risks of making predictions on ${BRAND_NAME}, but it cannot list every risk.`, DRAFT_EN],
  sections: [
    {
      h: '1. You may lose everything you put in',
      p: ['Predictions can be lost: when you are wrong, the whole amount you put in is lost. Only use money you can afford to lose.'],
    },
    {
      h: '2. Blockchain transfers',
      p: ['A confirmed blockchain transfer cannot be reversed; transfers on the wrong network, to the wrong address or of other tokens cannot be recovered.'],
    },
    {
      h: '3. Legal and regulatory',
      p: ['You are responsible for making sure that using the Service is lawful where you live. Regulatory and licence information: to be provided.'],
    },
  ],
};

export const LEGAL: Record<LegalPage, Record<Lang, LegalDoc>> = {
  terms: { 'zh-CN': termsZh, en: termsEn },
  privacy: { 'zh-CN': privacyZh, en: privacyEn },
  risk: { 'zh-CN': riskZh, en: riskEn },
};

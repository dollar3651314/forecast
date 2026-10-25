/**
 * 后台数据接口（页面只能通过这里取数据）。
 * 两种模式（构建变量 VITE_API_MODE，见 ./mode.ts）：
 * - real（默认）：全部调用后端（./real.ts；S18a 登录、系统、客户、KYC、资金、通知，S18b 仪表盘、交易监控、风控、品种与行情、交易参数）；
 * - mock：全部用 src/mocks 的内存实现（原型预览站、单元测试）；只有"切换演示身份"和原型场景是原型独有的。
 * 注释里是真实接口路径（《项目索引》第 2.8 节）。
 *
 * Admin data API. Pages import from here only. "real" mode calls the backend for S18a endpoints; "mock" keeps the prototype.
 */
import * as mock from '../mocks/handlers';
import * as fm from '../mocks/fc';
import * as runtime from '../mocks/runtime';
import { IS_MOCK } from './mode';
import * as real from './real';
import * as rf from './realFc';
import { ApiError } from './types';

export type { CurrentAdmin } from './types';
export type { ProtoIdentity } from '../mocks/handlers';
export type { Scenario } from '../mocks/runtime';
export { API_MODE, IS_MOCK } from './mode';
export { onAuthEvent } from './http';

// ---- 认证 ----
/** POST /api/admin/auth/login（令牌只在 HttpOnly Cookie 里，响应不带 token） */
export const login: typeof real.login = IS_MOCK ? mock.login : real.login;
/** POST /api/admin/auth/logout */
export const logout: typeof real.logout = IS_MOCK ? () => Promise.resolve() : real.logout;
/** POST /api/admin/auth/change-password（其他设备退出） */
export const changePassword: typeof real.changePassword = IS_MOCK ? mock.changePassword : real.changePassword;
/** GET /api/admin/me（当前管理员、角色、权限点、可见菜单）。模拟层按 adminId 取演示身份 */
export const getCurrentAdmin = (adminId?: number): ReturnType<typeof real.getCurrentAdmin> =>
  IS_MOCK ? mock.getCurrentAdmin(adminId ?? 1) : real.getCurrentAdmin();
/** 原型专用：切换演示身份（正式版没有这个接口；真实接口模式下调用直接失败，也不会打包模拟层） */
export const switchIdentity: typeof mock.switchIdentity = IS_MOCK ? mock.switchIdentity : () => Promise.reject(new Error('原型专用：真实接口模式没有切换演示身份'));

// ---- 仪表盘 ----
/** GET /api/admin/dashboard */
export const getDashboard: typeof real.getDashboard = IS_MOCK ? mock.getDashboard : real.getDashboard;
/** GET /api/admin/menu-badges */
export const getMenuBadges: typeof real.getMenuBadges = IS_MOCK ? mock.getMenuBadges : real.getMenuBadges;

// ---- 客户 ----
/** GET /api/admin/customers */
export const listCustomers: typeof real.listCustomers = IS_MOCK ? mock.listCustomers : real.listCustomers;
/** GET /api/admin/customers/{id} */
export const getCustomer: typeof real.getCustomer = IS_MOCK ? mock.getCustomer : real.getCustomer;
/** POST /api/admin/customers/{id}/freeze（高危） */
export const setCustomerFrozen: typeof real.setCustomerFrozen = IS_MOCK ? mock.setCustomerFrozen : real.setCustomerFrozen;
/** PUT /api/admin/customers/{id}/profile（高危，v2 E1：姓名、出生日期、国籍） */
export const updateCustomerProfile: typeof real.updateCustomerProfile = IS_MOCK ? mock.updateCustomerProfile : real.updateCustomerProfile;
/** GET /api/admin/customers/search（发通知时选客户） */
export const searchCustomers: typeof real.searchCustomers = IS_MOCK ? mock.searchCustomers : real.searchCustomers;

// ---- KYC ----
/** GET /api/admin/kyc */
export const listKyc: typeof real.listKyc = IS_MOCK ? mock.listKyc : real.listKyc;
/** GET /api/admin/kyc/{id} */
export const getKyc: typeof real.getKyc = IS_MOCK ? mock.getKyc : real.getKyc;
/** POST /api/admin/kyc/{id}/review */
export const reviewKyc: typeof real.reviewKyc = IS_MOCK ? mock.reviewKyc : real.reviewKyc;
/** GET /api/admin/kyc/{id}/images/{front|back|selfie} 的地址；原型没有照片，返回 null（显示占位图） */
export const kycImageUrl: typeof real.kycImageUrl = IS_MOCK ? () => null : real.kycImageUrl;

// ---- 资金 ----
/** GET /api/admin/deposits */
export const listDeposits: typeof real.listDeposits = IS_MOCK ? mock.listDeposits : real.listDeposits;
/** GET /api/admin/withdrawals */
export const listWithdrawals: typeof real.listWithdrawals = IS_MOCK ? mock.listWithdrawals : real.listWithdrawals;
/** GET /api/admin/withdrawals/summary */
export const getWithdrawalSummary: typeof real.getWithdrawalSummary = IS_MOCK ? mock.getWithdrawalSummary : real.getWithdrawalSummary;
/** GET /api/admin/withdrawals/{id} */
export const getWithdrawal: typeof real.getWithdrawal = IS_MOCK ? mock.getWithdrawal : real.getWithdrawal;
/** POST /api/admin/withdrawals/{id}/approve */
export const approveWithdrawal: typeof real.approveWithdrawal = IS_MOCK ? mock.approveWithdrawal : real.approveWithdrawal;
/** POST /api/admin/withdrawals/{id}/reject */
export const rejectWithdrawal: typeof real.rejectWithdrawal = IS_MOCK ? mock.rejectWithdrawal : real.rejectWithdrawal;
/** POST /api/admin/withdrawals/{id}/emergency-cancel（高危） */
export const emergencyCancelWithdrawal: typeof real.emergencyCancelWithdrawal = IS_MOCK ? mock.emergencyCancelWithdrawal : real.emergencyCancelWithdrawal;
// ---- v2 提现自动审批（技术方案/v2-提现自动审批.md 第 7 节） ----
/** GET /api/admin/withdraw-auto-approve：总开关、全部规则（现在 13 条）、版本 */
export const getAutoApproveSettings: typeof real.getAutoApproveSettings = IS_MOCK ? mock.getAutoApproveSettings : real.getAutoApproveSettings;
/** GET /api/admin/withdraw-auto-approve/changes：最近 20 次修改 */
export const listAutoApproveChanges: typeof real.listAutoApproveChanges = IS_MOCK ? mock.listAutoApproveChanges : real.listAutoApproveChanges;
/** PUT /api/admin/withdraw-auto-approve（高危，withdrawal:auto_rule_edit；版本不对 409 CONFIG_CHANGED） */
export const updateAutoApproveSettings: typeof real.updateAutoApproveSettings = IS_MOCK ? mock.updateAutoApproveSettings : real.updateAutoApproveSettings;
/** GET /api/admin/recon */
export const listRecon: typeof real.listRecon = IS_MOCK ? mock.listRecon : real.listRecon;
/** GET /api/admin/hot-wallets：热钱包余额和告警下限 */
export const listHotWallets: typeof real.listHotWallets = IS_MOCK ? mock.listHotWallets : real.listHotWallets;
// ---- v2 资金归集（技术方案/v2-半自动归集.md v1.2）：第一阶段以太坊、BSC 接后端；TRON 专用的接口（待签名清单、导入签名、广播、作废、冷钱包地址）是下一阶段，
// 真实接口模式下直接失败（页面也显示"下一阶段"，config/sweep.ts 的 SWEEP_NEXT_PHASE） ----
const sweepNextPhase = () => Promise.reject(new ApiError('errors.sweepNextPhase'));
/** GET /api/admin/sweep/overview */
export const getSweepOverview: typeof mock.getSweepOverview = IS_MOCK ? mock.getSweepOverview : real.getSweepOverview;
/** POST /api/admin/sweep/preview */
export const previewSweep: typeof mock.previewSweep = IS_MOCK ? mock.previewSweep : real.previewSweep;
/** POST /api/admin/sweep/batches（高危，sweep:operate）：以太坊、BSC 执行归集；TRON 生成待签名清单（下一阶段） */
export const runSweep: typeof mock.runSweep = IS_MOCK ? mock.runSweep : real.runSweep;
/** GET /api/admin/sweep/batches */
export const listSweepBatches: typeof mock.listSweepBatches = IS_MOCK ? mock.listSweepBatches : real.listSweepBatches;
/** GET /api/admin/sweep/batches/{no} */
export const getSweepBatch: typeof mock.getSweepBatch = IS_MOCK ? mock.getSweepBatch : real.getSweepBatch;
/** POST /api/admin/sweep/batches/{no}/retry（高危） */
export const retrySweepBatch: typeof mock.retrySweepBatch = IS_MOCK ? mock.retrySweepBatch : real.retrySweepBatch;
/** GET /api/admin/sweep/batches/{no}/unsigned（TRON，下一阶段） */
export const downloadSweepUnsigned: typeof mock.downloadSweepUnsigned = IS_MOCK ? mock.downloadSweepUnsigned : sweepNextPhase;
/** 原型专用：演示用的签名文件（正式版没有这个接口） */
export const demoSweepSignedFile: typeof mock.demoSweepSignedFile = IS_MOCK ? mock.demoSweepSignedFile : sweepNextPhase;
/** POST /api/admin/sweep/batches/{no}/signed（TRON，下一阶段，高危） */
export const importSweepSigned: typeof mock.importSweepSigned = IS_MOCK ? mock.importSweepSigned : sweepNextPhase;
/** POST /api/admin/sweep/batches/{no}/broadcast（TRON，下一阶段，高危） */
export const broadcastSweep: typeof mock.broadcastSweep = IS_MOCK ? mock.broadcastSweep : sweepNextPhase;
/** POST /api/admin/sweep/batches/{no}/cancel（TRON，下一阶段，高危） */
export const cancelSweepBatch: typeof mock.cancelSweepBatch = IS_MOCK ? mock.cancelSweepBatch : sweepNextPhase;
/** GET /api/admin/sweep/settings */
export const getSweepSettings: typeof mock.getSweepSettings = IS_MOCK ? mock.getSweepSettings : real.getSweepSettings;
/** PUT /api/admin/sweep/settings（高危，sweep:config） */
export const updateSweepSettings: typeof mock.updateSweepSettings = IS_MOCK ? mock.updateSweepSettings : real.updateSweepSettings;
/** POST /api/admin/sweep/targets（TRON 冷钱包地址，24 小时后生效，下一阶段，高危） */
export const requestTronSweepTarget: typeof mock.requestTronSweepTarget = IS_MOCK ? mock.requestTronSweepTarget : sweepNextPhase;
/** POST /api/admin/sweep/targets/cancel（下一阶段，高危） */
export const cancelTronSweepTarget: typeof mock.cancelTronSweepTarget = IS_MOCK ? mock.cancelTronSweepTarget : sweepNextPhase;
/** POST /api/admin/recon/{id}/resolve */
export const resolveRecon: typeof real.resolveRecon = IS_MOCK ? mock.resolveRecon : real.resolveRecon;
/** GET /api/admin/tasks */
export const listTasks: typeof real.listTasks = IS_MOCK ? mock.listTasks : real.listTasks;
/** POST /api/admin/tasks/{id}/retry */
export const retryTask: typeof real.retryTask = IS_MOCK ? mock.retryTask : real.retryTask;

// ---- 通知 ----
/** GET /api/admin/notify/templates */
export const listTemplates: typeof real.listTemplates = IS_MOCK ? mock.listTemplates : real.listTemplates;
/** PUT /api/admin/notify/templates/{code} */
export const updateTemplate: typeof real.updateTemplate = IS_MOCK ? mock.updateTemplate : real.updateTemplate;
/** POST /api/admin/notify/send（高危） */
export const sendManualNotification: typeof real.sendManualNotification = IS_MOCK ? mock.sendManualNotification : real.sendManualNotification;
/** GET /api/admin/notify/records */
export const listNotifyRecords: typeof real.listNotifyRecords = IS_MOCK ? mock.listNotifyRecords : real.listNotifyRecords;

// ---- 系统 ----
/** GET /api/admin/admins */
export const listAdmins: typeof real.listAdmins = IS_MOCK ? mock.listAdmins : real.listAdmins;
/** POST /api/admin/admins（高危；返回只显示一次的临时密码） */
export const createAdmin: typeof real.createAdmin = IS_MOCK ? mock.createAdmin : real.createAdmin;
/** PUT /api/admin/admins/{id}（高危） */
export const updateAdmin: typeof real.updateAdmin = IS_MOCK ? mock.updateAdmin : real.updateAdmin;
/** POST /api/admin/admins/{id}/status（高危） */
export const setAdminStatus: typeof real.setAdminStatus = IS_MOCK ? mock.setAdminStatus : real.setAdminStatus;
/** POST /api/admin/admins/{id}/reset-password（高危；返回只显示一次的临时密码） */
export const resetAdminPassword: typeof real.resetAdminPassword = IS_MOCK ? mock.resetAdminPassword : real.resetAdminPassword;
/** GET /api/admin/roles */
export const listRoles: typeof real.listRoles = IS_MOCK ? mock.listRoles : real.listRoles;
/** POST /api/admin/roles | PUT /api/admin/roles/{id}（高危） */
export const saveRole: typeof real.saveRole = IS_MOCK ? mock.saveRole : real.saveRole;
/** DELETE /api/admin/roles/{id}（高危） */
export const deleteRole: typeof real.deleteRole = IS_MOCK ? mock.deleteRole : real.deleteRole;
/** GET /api/admin/menus */
export const listMenus: typeof real.listMenus = IS_MOCK ? mock.listMenus : real.listMenus;
/** PUT /api/admin/menus/{key}（顺序、是否显示） */
export const updateMenu: typeof real.updateMenu = IS_MOCK ? mock.updateMenu : real.updateMenu;
/** GET /api/admin/permissions */
export const listPermissions: typeof real.listPermissions = IS_MOCK ? mock.listPermissions : real.listPermissions;
/** GET /api/admin/audit-logs */
export const listAuditLogs: typeof real.listAuditLogs = IS_MOCK ? mock.listAuditLogs : real.listAuditLogs;
/** GET /api/admin/audit-logs/actions */
export const listAuditActions: typeof real.listAuditActions = IS_MOCK ? mock.listAuditActions : real.listAuditActions;
/** GET /api/admin/configs */
export const listSystemConfigs: typeof real.listSystemConfigs = IS_MOCK ? mock.listSystemConfigs : real.listSystemConfigs;
/** PUT /api/admin/configs/{key}（高危） */
export const updateSystemConfig: typeof real.updateSystemConfig = IS_MOCK ? mock.updateSystemConfig : real.updateSystemConfig;
/** GET /api/admin/params/{fee|betting}（forecast S3 参数设置：fee:edit / betting_params:edit） */
export const listParams: typeof real.listParams = IS_MOCK ? mock.listParams : real.listParams;
/** PUT /api/admin/params/{fee|betting}/{key}（高危；expected 不对 409 CONFIG_CHANGED） */
export const updateParam: typeof real.updateParam = IS_MOCK ? mock.updateParam : real.updateParam;

// ---- forecast S9：仪表盘彩池部分、市场管理、自动生成、体育、裁决台、争议、结算、对账、补偿、报表（字段和后端一致：api/fcTypes.ts；真实接口 api/realFc.ts） ----
export const getMarketDashboard: typeof rf.getMarketDashboard = IS_MOCK ? fm.getMarketDashboard : rf.getMarketDashboard;
export const listMarkets: typeof rf.listMarkets = IS_MOCK ? fm.listMarkets : rf.listMarkets;
export const getMarketCounts: typeof rf.getMarketCounts = IS_MOCK ? fm.getMarketCounts : rf.getMarketCounts;
export const getMarket: typeof rf.getMarket = IS_MOCK ? fm.getMarket : rf.getMarket;
export const createMarket: typeof rf.createMarket = IS_MOCK ? fm.createMarket : rf.createMarket;
export const updateMarket: typeof rf.updateMarket = IS_MOCK ? fm.updateMarket : rf.updateMarket;
export const deleteMarket: typeof rf.deleteMarket = IS_MOCK ? fm.deleteMarket : rf.deleteMarket;
export const submitMarket: typeof rf.submitMarket = IS_MOCK ? fm.submitMarket : rf.submitMarket;
export const returnMarket: typeof rf.returnMarket = IS_MOCK ? fm.returnMarket : rf.returnMarket;
export const publishMarket: typeof rf.publishMarket = IS_MOCK ? fm.publishMarket : rf.publishMarket;
export const setTodaySlate: typeof rf.setTodaySlate = IS_MOCK ? fm.setTodaySlate : rf.setTodaySlate;
export const getOpenLimit: typeof rf.getOpenLimit = IS_MOCK ? fm.getOpenLimit : rf.getOpenLimit;
// forecast S13：来源统计
export const getGrowthReport: typeof rf.getGrowthReport = IS_MOCK ? fm.getGrowthReport : rf.getGrowthReport;
export const exportGrowth: typeof rf.exportGrowth = IS_MOCK ? fm.exportGrowth : rf.exportGrowth;
export const pauseMarket: typeof rf.pauseMarket = IS_MOCK ? fm.pauseMarket : rf.pauseMarket;
export const resumeMarket: typeof rf.resumeMarket = IS_MOCK ? fm.resumeMarket : rf.resumeMarket;
export const cancelMarket: typeof rf.cancelMarket = IS_MOCK ? fm.cancelMarket : rf.cancelMarket;
export const advanceClose: typeof rf.advanceClose = IS_MOCK ? fm.advanceClose : rf.advanceClose;
export const clarifyMarket: typeof rf.clarifyMarket = IS_MOCK ? fm.clarifyMarket : rf.clarifyMarket;
export const changeFee: typeof rf.changeFee = IS_MOCK ? fm.changeFee : rf.changeFee;
export const voidMarket: typeof rf.voidMarket = IS_MOCK ? fm.voidMarket : rf.voidMarket;
export const getMarketBets: typeof rf.getMarketBets = IS_MOCK ? fm.getMarketBets : rf.getMarketBets;
export const getSettlement: typeof rf.getSettlement = IS_MOCK ? fm.getSettlement : rf.getSettlement;
export const getEvidence: typeof rf.getEvidence = IS_MOCK ? fm.getEvidence : rf.getEvidence;
export const listAutogens: typeof rf.listAutogens = IS_MOCK ? fm.listAutogens : rf.listAutogens;
export const createAutogen: typeof rf.createAutogen = IS_MOCK ? fm.createAutogen : rf.createAutogen;
export const updateAutogen: typeof rf.updateAutogen = IS_MOCK ? fm.updateAutogen : rf.updateAutogen;
export const enableAutogen: typeof rf.enableAutogen = IS_MOCK ? fm.enableAutogen : rf.enableAutogen;
export const disableAutogen: typeof rf.disableAutogen = IS_MOCK ? fm.disableAutogen : rf.disableAutogen;
export const listRuleTemplates: typeof rf.listRuleTemplates = IS_MOCK ? fm.listRuleTemplates : rf.listRuleTemplates;
export const updateRuleTemplate: typeof rf.updateRuleTemplate = IS_MOCK ? fm.updateRuleTemplate : rf.updateRuleTemplate;
export const listFixtures: typeof rf.listFixtures = IS_MOCK ? fm.listFixtures : rf.listFixtures;
export const getFixture: typeof rf.getFixture = IS_MOCK ? fm.getFixture : rf.getFixture;
export const syncFixtures: typeof rf.syncFixtures = IS_MOCK ? fm.syncFixtures : rf.syncFixtures;
export const searchCandidates: typeof rf.searchCandidates = IS_MOCK ? fm.searchCandidates : rf.searchCandidates;
export const confirmMapping: typeof rf.confirmMapping = IS_MOCK ? fm.confirmMapping : rf.confirmMapping;
export const clearMapping: typeof rf.clearMapping = IS_MOCK ? fm.clearMapping : rf.clearMapping;
export const generateDrafts: typeof rf.generateDrafts = IS_MOCK ? fm.generateDrafts : rf.generateDrafts;
export const listSportsTemplates: typeof rf.listSportsTemplates = IS_MOCK ? fm.listSportsTemplates : rf.listSportsTemplates;
export const updateSportsTemplate: typeof rf.updateSportsTemplate = IS_MOCK ? fm.updateSportsTemplate : rf.updateSportsTemplate;
export const listCases: typeof rf.listCases = IS_MOCK ? fm.listCases : rf.listCases;
export const getCaseCounts: typeof rf.getCaseCounts = IS_MOCK ? fm.getCaseCounts : rf.getCaseCounts;
export const getCase: typeof rf.getCase = IS_MOCK ? fm.getCase : rf.getCase;
export const previewSettle: typeof rf.previewSettle = IS_MOCK ? fm.previewSettle : rf.previewSettle;
export const propose: typeof rf.propose = IS_MOCK ? fm.propose : rf.propose;
export const confirmProposal: typeof rf.confirmProposal = IS_MOCK ? fm.confirmProposal : rf.confirmProposal;
export const returnProposal: typeof rf.returnProposal = IS_MOCK ? fm.returnProposal : rf.returnProposal;
export const decideDirect: typeof rf.decideDirect = IS_MOCK ? fm.decideDirect : rf.decideDirect;
export const holdCase: typeof rf.holdCase = IS_MOCK ? fm.holdCase : rf.holdCase;
export const releaseCase: typeof rf.releaseCase = IS_MOCK ? fm.releaseCase : rf.releaseCase;
export const attachmentUrl: typeof rf.attachmentUrl = IS_MOCK ? fm.attachmentUrl : rf.attachmentUrl;
export const uploadAttachment: typeof rf.uploadAttachment = IS_MOCK ? fm.uploadAttachment : rf.uploadAttachment;
export const listDisputes: typeof rf.listDisputes = IS_MOCK ? fm.listDisputes : rf.listDisputes;
export const getDisputeCase: typeof rf.getDisputeCase = IS_MOCK ? fm.getDisputeCase : rf.getDisputeCase;
export const decideDisputes: typeof rf.decideDisputes = IS_MOCK ? fm.decideDisputes : rf.decideDisputes;
export const listSettleRuns: typeof rf.listSettleRuns = IS_MOCK ? fm.listSettleRuns : rf.listSettleRuns;
export const getSettleRun: typeof rf.getSettleRun = IS_MOCK ? fm.getSettleRun : rf.getSettleRun;
export const retrySettleRun: typeof rf.retrySettleRun = IS_MOCK ? fm.retrySettleRun : rf.retrySettleRun;
export const listPoolIssues: typeof rf.listPoolIssues = IS_MOCK ? fm.listPoolIssues : rf.listPoolIssues;
export const resolvePoolIssue: typeof rf.resolvePoolIssue = IS_MOCK ? fm.resolvePoolIssue : rf.resolvePoolIssue;
export const previewCompensation: typeof rf.previewCompensation = IS_MOCK ? fm.previewCompensation : rf.previewCompensation;
export const compensate: typeof rf.compensate = IS_MOCK ? fm.compensate : rf.compensate;
export const listCompensations: typeof rf.listCompensations = IS_MOCK ? fm.listCompensations : rf.listCompensations;
export const getCompensation: typeof rf.getCompensation = IS_MOCK ? fm.getCompensation : rf.getCompensation;
export const getReport: typeof rf.getReport = IS_MOCK ? fm.getReport : rf.getReport;
// forecast S10：风控告警、员工账户、关联账户
export const listRiskAlerts: typeof rf.listRiskAlerts = IS_MOCK ? fm.listRiskAlerts : rf.listRiskAlerts;
export const getRiskAlertCounts: typeof rf.getRiskAlertCounts = IS_MOCK ? fm.getRiskAlertCounts : rf.getRiskAlertCounts;
export const setRiskAlertStatus: typeof rf.setRiskAlertStatus = IS_MOCK ? fm.setRiskAlertStatus : rf.setRiskAlertStatus;
export const getCustomerLinks: typeof rf.getCustomerLinks = IS_MOCK ? fm.getCustomerLinks : rf.getCustomerLinks;
export const setCustomerStaff: typeof rf.setCustomerStaff = IS_MOCK ? fm.setCustomerStaff : rf.setCustomerStaff;

// ---- 原型场景（只在 mock 模式下有界面入口） ----
// 真实接口模式下恒为"正常数据"、版本号恒为 0；按编译期常量选，real 构建不打包模拟层（vite.config.ts 的 mockFree）
export const proto: Pick<typeof runtime, 'getScenario' | 'setScenario' | 'subscribeScenario' | 'getScenarioVersion'> = IS_MOCK
  ? {
      getScenario: runtime.getScenario,
      setScenario: runtime.setScenario,
      subscribeScenario: runtime.subscribeScenario,
      getScenarioVersion: runtime.getScenarioVersion,
    }
  : {
      getScenario: () => 'normal',
      setScenario: () => {},
      subscribeScenario: () => () => {},
      getScenarioVersion: () => 0,
    };

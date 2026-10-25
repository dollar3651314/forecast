/**
 * 原型专用接口：场景切换、模拟推送。正式开发时整个文件删除，
 * 只有原型面板（components/ProtoPanel）引用它。
 */
import { getProtoState, getScenarioVersion, setProtoState, subscribeProto, type ProtoState, type Scenario } from '../mocks/scenario';
import { protoKycStatus, protoSetKyc, resetPrototypeData, simulatePush, type PushKind } from '../mocks/proto';
import { setMockDelay } from '../mocks/delay';

export type { ProtoState, Scenario, PushKind };
export { getProtoState, getScenarioVersion, setProtoState, subscribeProto, protoKycStatus, protoSetKyc, resetPrototypeData, simulatePush, setMockDelay };

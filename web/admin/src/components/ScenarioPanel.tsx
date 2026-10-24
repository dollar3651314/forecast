/**
 * 原型场景面板（右下角）：切换正常数据 / 空数据 / 接口很慢 / 接口出错，当前页面立刻按该场景重新加载。
 * 只在原型里存在（规则 C32、C42）。
 */
import { useState, useSyncExternalStore } from 'react';
import { Button, Card, Radio, Space, Typography } from 'antd';
import { ExperimentOutlined, CloseOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { IS_MOCK, proto, type Scenario } from '../api';

const SCENARIOS: Scenario[] = ['normal', 'empty', 'slow', 'error'];

/** 只在 mock 模式（原型预览站）下显示 */
export function ScenarioPanel() {
  return IS_MOCK ? <ScenarioPanelInner /> : null;
}

function ScenarioPanelInner() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const scenario = useSyncExternalStore(proto.subscribeScenario, proto.getScenario, proto.getScenario);

  if (!open) {
    return (
      <Button
        className="scenario-fab"
        icon={<ExperimentOutlined />}
        onClick={() => setOpen(true)}
        aria-label={t('proto.scenarioTitle')}
        type={scenario === 'normal' ? 'default' : 'primary'}
      >
        {t('proto.scenarioTitle')}
        {scenario !== 'normal' ? ` · ${t(`proto.scenario.${scenario}`)}` : ''}
      </Button>
    );
  }
  return (
    <Card
      className="scenario-panel"
      size="small"
      title={
        <Space>
          <ExperimentOutlined />
          {t('proto.scenarioTitle')}
        </Space>
      }
      extra={<Button type="text" size="small" icon={<CloseOutlined />} aria-label={t('common.close')} onClick={() => setOpen(false)} />}
    >
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
        {t('proto.scenarioHint')}
      </Typography.Paragraph>
      <Radio.Group value={scenario} onChange={(e) => proto.setScenario(e.target.value as Scenario)} aria-label={t('proto.scenarioTitle')}>
        <Space direction="vertical" size={4}>
          {SCENARIOS.map((s) => (
            <Radio key={s} value={s}>
              {t(`proto.scenario.${s}`)}
            </Radio>
          ))}
        </Space>
      </Radio.Group>
    </Card>
  );
}

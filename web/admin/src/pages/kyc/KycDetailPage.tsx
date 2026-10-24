/**
 * KYC 详情：资料 + 证件照片 + 通过 / 驳回（驳回必填原因）。
 * 照片用 GET /api/admin/kyc/{id}/images/{front|back|selfie} 显示（后台方案第 8 节，同源 Cookie 鉴权、不缓存）；
 * 原型（mock 模式）没有照片，显示占位图；照片缺失或加载失败时显示"没有这张照片"。
 */
import { useCallback, useState, type ReactNode } from 'react';
import { Alert, App, Button, Descriptions, Image, Skeleton, Space } from 'antd';
import { ArrowLeftOutlined, IdcardOutlined, CameraOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getKyc, kycImageUrl, reviewKyc } from '../../api';
import { fullTime } from '../../utils/display';

type ImageSide = 'front' | 'back' | 'selfie';

/** 一张证件照片：真实模式加载接口图片（可点击放大），原型或加载失败时显示占位 */
function DocImage({ kycId, side, icon }: { kycId: string; side: ImageSide; icon: ReactNode }) {
  const { t } = useTranslation();
  const url = kycImageUrl(kycId, side);
  const [failed, setFailed] = useState(false);
  const label = t(`kyc.img.${side}`);
  if (url && !failed) {
    return (
      <figure className="doc-img" style={{ margin: 0 }}>
        <Image src={url} alt={label} onError={() => setFailed(true)} preview={{ mask: t('kyc.zoom') }} />
        <figcaption>{label}</figcaption>
      </figure>
    );
  }
  return (
    <figure className="doc-ph" style={{ margin: 0 }} aria-label={label}>
      {icon}
      <b>{label}</b>
      <span>{url ? t('kyc.imageMissing') : t('kyc.placeholder')}</span>
    </figure>
  );
}
import { useApi } from '../../hooks/useApi';
import { useAction, useNoteDialog } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { kycTone } from '../../utils/status';

export function KycDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { modal } = App.useApp();
  const run = useAction();
  const note = useNoteDialog();
  const fetcher = useCallback(() => getKyc(id), [id]);
  const { data: k, loading, error, reload } = useApi(fetcher);

  const approve = () => {
    if (!k) return;
    modal.confirm({
      title: t('kyc.approveConfirm'),
      content: t('kyc.approveContent', { name: k.name }),
      okText: t('kyc.approve'),
      cancelText: t('common.cancel'),
      onOk: () => run(() => reviewKyc(k.id, true), t('kyc.approved'), reload),
    });
  };

  const reject = () => {
    if (!k) return;
    note.ask({
      title: t('kyc.reject'),
      label: t('kyc.rejectReason'),
      placeholder: t('kyc.rejectPh'),
      okText: t('kyc.reject'),
      danger: true,
      successText: t('kyc.rejected'),
      onConfirm: (n) => reviewKyc(k.id, false, n),
      onDone: reload,
    });
  };

  const images: { key: ImageSide; icon: ReactNode }[] = [
    { key: 'front', icon: <IdcardOutlined /> },
    { key: 'back', icon: <IdcardOutlined /> },
    { key: 'selfie', icon: <CameraOutlined /> },
  ];

  return (
    <>
      <PageHeader
        title={
          <Space>
            <Button type="text" icon={<ArrowLeftOutlined />} aria-label={t('common.back')} onClick={() => navigate('/kyc')} />
            {t('kyc.detail')}
            {k ? <Num className="muted">{k.id}</Num> : null}
            {k ? <Pill tone={kycTone[k.status]}>{t(`kycStatus.${k.status}`)}</Pill> : null}
          </Space>
        }
        extra={
          k && k.status === 'pending' ? (
            <>
              <PermButton perm="kyc:review" type="primary" icon={<CheckOutlined />} onClick={approve}>
                {t('kyc.approve')}
              </PermButton>
              <PermButton perm="kyc:review" danger icon={<CloseOutlined />} onClick={reject}>
                {t('kyc.reject')}
              </PermButton>
            </>
          ) : null
        }
      />
      {error ? (
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
      ) : !k || loading ? (
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      ) : (
        <>
          {k.status === 'rejected' && k.rejectReason ? <Alert type="error" showIcon message={t('kyc.rejectedLine', { reason: k.rejectReason })} /> : null}
          <div className="card card-pad">
            <h2 className="card-title">{t('kyc.applicant')}</h2>
            <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
              <Descriptions.Item label={t('customers.customer')}>{k.name}</Descriptions.Item>
              <Descriptions.Item label={t('customers.email')}>{k.email}</Descriptions.Item>
              <Descriptions.Item label={t('customers.id')}><Num>{k.customerId}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.nationality')}>{k.nationality}</Descriptions.Item>
              <Descriptions.Item label={t('customers.birthDate')}><Num>{k.birthDate}</Num></Descriptions.Item>
              <Descriptions.Item label={t('kyc.docType')}>{t(`kyc.doc.${k.docType}`)}</Descriptions.Item>
              <Descriptions.Item label={t('kyc.docNumber')}><Num>{k.docNumber}</Num></Descriptions.Item>
              <Descriptions.Item label={t('kyc.submittedAt')}><Num>{fullTime(k.submittedAt)}</Num></Descriptions.Item>
              <Descriptions.Item label={t('kyc.reviewer')}>{k.reviewer ? `${k.reviewer} · ${fullTime(k.reviewedAt)}` : '—'}</Descriptions.Item>
            </Descriptions>
          </div>
          <div className="card card-pad">
            <h2 className="card-title">
              <span>{t('kyc.images')}</span>
              <span className="muted" style={{ fontSize: 12, fontWeight: 400 }}>{t('kyc.imagesHint')}</span>
            </h2>
            <div className="doc-images">
              {images.map((img) => (
                <DocImage key={img.key} kycId={k.id} side={img.key} icon={img.icon} />
              ))}
            </div>
          </div>
        </>
      )}
      {note.element}
    </>
  );
}

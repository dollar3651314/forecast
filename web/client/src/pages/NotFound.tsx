import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { EmptyState } from '../components/AsyncView';

export default function NotFound() {
  const { t } = useTranslation();
  return (
    <div className="page">
      <EmptyState title={t('common.notFound')} action={<Link to="/home" className="btn btn-primary">{t('nav.home')}</Link>} />
    </div>
  );
}

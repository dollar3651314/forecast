/**
 * 公开页面的接口返回 401（S13：服务器关掉了"不登录也能看"，forecast.public-browse.enabled=false）：没登录时去登录页，登录后回到这一页。
 * 登录了还 401 是登录失效，由 AuthProvider 处理（回到登录页）。
 */
import { Navigate, useLocation } from 'react-router-dom';
import { isApiError } from '../api/errors';
import { useAuth } from '../app/contexts';

function needsLogin(error: unknown): boolean {
  return isApiError(error) && error.reason === 'unauthorized';
}

export function GuestGate({ error }: { error: unknown }) {
  const { session } = useAuth();
  const location = useLocation();
  if (!needsLogin(error) || session) return null;
  return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
}

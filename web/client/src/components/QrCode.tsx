/**
 * 二维码：用开源库 qrcode（MIT）在本地生成矩阵，再画成 SVG。不调用任何外部服务。
 */
import { useMemo } from 'react';
import QRCode from 'qrcode';

export function QrCode({ value, size = 168, label }: { value: string; size?: number; label: string }) {
  const path = useMemo(() => {
    const qr = QRCode.create(value, { errorCorrectionLevel: 'M' });
    const n = qr.modules.size;
    let d = '';
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (qr.modules.get(x, y)) d += `M${x + 2} ${y + 2}h1v1h-1z`;
      }
    }
    return { d, n: n + 4 };
  }, [value]);
  return (
    <svg className="qr" width={size} height={size} viewBox={`0 0 ${path.n} ${path.n}`} role="img" aria-label={label} shapeRendering="crispEdges">
      <rect width={path.n} height={path.n} fill="#ffffff" />
      <path d={path.d} fill="#0f172a" />
    </svg>
  );
}

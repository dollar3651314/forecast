/**
 * 浏览器标签页的标题（S13，NF-14：每个页面的标题按内容生成）。服务器给搜索引擎的标题在后端（market/pub/SeoPages）；
 * 这里是浏览器里切换页面以后的标题，格式一样："页面 | 品牌"。title 为空时只显示品牌。
 */
import { useEffect } from 'react';
import { BRAND_NAME } from '../lib/brand';

export function useTitle(title: string | undefined): void {
  useEffect(() => {
    document.title = title ? `${title} | ${BRAND_NAME}` : BRAND_NAME;
  }, [title]);
}

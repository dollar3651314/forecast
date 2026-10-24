/** 显示姓名：中文名按"姓 + 名"，其他按"名 中间名 姓" */
export function displayName(p: { firstName: string; middleName: string; lastName: string }, lang: string): string {
  const cjk = /[一-鿿]/.test(p.firstName + p.lastName);
  if (cjk && lang !== 'en') return `${p.lastName}${p.middleName}${p.firstName}`;
  return [p.firstName, p.middleName, p.lastName].filter(Boolean).join(' ');
}

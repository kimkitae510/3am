// 목업 표기: 오늘 → "오전 3:02", 어제 → "어제", 올해 → "6월 28일", 그 외 → "2024. 6. 28."
export function formatListTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();

  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();

  if (sameDay(d, now)) {
    const h = d.getHours();
    const ampm = h < 12 ? '오전' : '오후';
    const h12 = h % 12 === 0 ? 12 : h % 12;
    return `${ampm} ${h12}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (sameDay(d, yesterday)) return '어제';

  if (d.getFullYear() === now.getFullYear()) {
    return `${d.getMonth() + 1}월 ${d.getDate()}일`;
  }
  return `${d.getFullYear()}. ${d.getMonth() + 1}. ${d.getDate()}.`;
}

// 채팅 말풍선 구분선용: "오전 3:00"
export function formatClock(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const h = d.getHours();
  const ampm = h < 12 ? '오전' : '오후';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${ampm} ${h12}:${String(d.getMinutes()).padStart(2, '0')}`;
}

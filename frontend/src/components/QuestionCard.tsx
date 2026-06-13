import styles from "./QuestionCard.module.css";

// 상담자가 물은 것은 진짜 메시지처럼 대화 목록에 얹어 같은 렌더를 태운다(ChatPage의
// viewMessages) — 따로 그리면 프사, 이름, 꼬리, 묶음 규칙을 두 곳에서 관리하게 된다.
// 여기 남은 것은 대화에 없는 요소 하나뿐이다.

// 답 대신 넘기는 것도 유저가 하는 말이라 유저 말풍선 자리에 칩으로 둔다.
// 조작 버튼이 아니라 답변 선택지로 읽혀야 대화가 안 끊긴다.
export function QuestionSkip({ onClick }: { onClick: () => void }) {
  return (
    <button className={styles.skip} onClick={onClick}>
      건너뛰기
    </button>
  );
}

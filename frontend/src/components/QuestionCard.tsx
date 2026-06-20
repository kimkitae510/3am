import styles from "./QuestionCard.module.css";

// 상담자가 물은 것은 진짜 메시지처럼 대화 목록에 얹어 같은 렌더를 태운다(ChatPage의
// viewMessages) — 따로 그리면 프사, 이름, 꼬리, 묶음 규칙을 두 곳에서 관리하게 된다.
// 여기 남은 것은 대화에 없는 요소 하나뿐이다.

// 답 대신 넘기는 것도, 앞 답을 고쳐 쓰겠다는 것도 유저가 하는 말이라 유저 말풍선 자리에
// 칩으로 둔다. 조작 버튼이 아니라 답변 선택지로 읽혀야 대화가 안 끊긴다.
// 이전은 첫 질문에서는 갈 곳이 없어 안 그린다(onBack 없음).
//
// 몇 번째 질문인지도 여기서 맡는다 — 말풍선 안에 "(1/3)"으로 넣으면 상담자가 서식을 읽는
// 꼴이 된다. 지나간 턴에는 안 붙는다: 질문과 답이 짝지어 다 보이는 자리라 번호는 소음이다.
export function QuestionActions({
  step,
  total,
  onBack,
  onSkip,
}: {
  step: number;
  total: number;
  onBack?: () => void;
  onSkip: () => void;
}) {
  return (
    <div className={styles.actions}>
      {/* 하나만 물었으면 셈이 필요 없다 */}
      {total > 1 && (
        <span className={styles.step}>
          {step}/{total}
        </span>
      )}
      {onBack && (
        <button className={styles.chip} onClick={onBack}>
          이전
        </button>
      )}
      <button className={styles.chip} onClick={onSkip}>
        건너뛰기
      </button>
    </div>
  );
}

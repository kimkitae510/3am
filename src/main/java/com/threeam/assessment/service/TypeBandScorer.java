package com.threeam.assessment.service;

import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReplacementStage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// 확률 계산 v2: 유형이 대역을 정하고(1층), 요인 판정이 대역 안에서 조정한다(2층).
// LLM은 유형과 요인의 '판정'만 내리고 숫자는 전부 여기 상수다 — 같은 판정이면 언제나 같은 확률.
// (v1 ReunionScorer는 LLM이 신호마다 점수를 발명해 같은 사연에도 확률이 출렁였다.)
// 대역과 폭의 근거는 루브릭 문서(루브릭v2.md) — 커뮤니티/업계 조사 기반 서열에 우리 설계값.
@Component
public class TypeBandScorer {

    private static final int ABS_MIN = 3;
    private static final int ABS_MAX = 95; // 96~100은 상대의 유효한 재회 제안(확정 100) 몫

    // 대역은 앵커지 칸막이가 아니다 — 폭을 12~16으로 줄였다(구 20~35). 넓은 대역은 유형
    // 구분을 무디게 하고 경계 오판 한 번의 낙차를 키웠다. 0~100을 꽉 채울 이유도 없다:
    // 극단은 헤드룸(±10)과 절대 3~95가 맡고, 대역 안 변별은 요인이 한다.
    //
    // 상단(충동, 상황)은 일반 인구의 재회율이 아니라 '아직 안 돌아와서 분석을 받으러 온
    // 사람'의 값이어야 한다. 홧김에 헤어지고 사흘 뒤 붙은 커플은 이 서비스에 오지 않는다 —
    // 빨리 붙는 유형일수록 표본에서 빠져나가므로 그만큼 낮춘다.
    // 하단은 반대다. 신뢰붕괴 상한 15는 자백하고 검증까지 받아들인 판(사례 실측 2건)을
    // 표현하지 못했다. 환승은 리바운드 실패율이 높아 시간이 장벽을 치우는 반면 권태식음은
    // 치울 장벽조차 없어(항구적 사유), 벌려뒀던 둘의 간극을 좁힌다.
    private static final Map<BreakupType, Band> BANDS = new EnumMap<>(Map.of(
            BreakupType.IMPULSIVE, new Band(52, 68),
            BreakupType.SITUATIONAL, new Band(46, 62),
            BreakupType.EXTERNAL, new Band(36, 50),
            BreakupType.BURNOUT, new Band(20, 33),
            BreakupType.FADED, new Band(20, 32),
            BreakupType.TRANSFER, new Band(15, 27),
            BreakupType.RESOLVED, new Band(10, 22),
            BreakupType.TRUST_BROKEN, new Band(6, 18)));

    // 점프는 이별 후에 관측된 상태다. 재회는 '왜 헤어졌나'보다 '이별 후에 무엇이 있었나'로
    // 더 잘 예측되므로(온오프 종단 연구) 점프가 사유보다 무겁다. 다만 사유를 지우지는
    // 않는다 — 통째로 교체하던 구조에서는 외도로 헤어진 판에 안부 연락 한 번이 오면 사유가
    // 사라지고 접촉재개 대역만 남았다. pull은 사유 대역을 점프 쪽으로 끌어당기는 비율이고,
    // 1.0이면 종전과 같은 완전 교체다.
    //
    // up=true는 좋은 소식(올리기만 한다), false는 나쁜 소식(내리기만 한다). 연락이 왔다는
    // 사실이 확률을 낮추는 근거가 될 수는 없다 — 사유 대역이 점프 대역보다 이미 높을 때
    // 끌어당김이 확률을 깎던 것을 막는 단조성 제약이다. 연락의 세부는 상대신호 요인이 잰다.
    private static final Map<JumpRule, Jump> JUMPS = new EnumMap<>(Map.of(
            // 상대의 마음이 열려 있음이 확인된 판. 이별을 원하지 않은 쪽이 상대라는 것은
            // 온오프 연구에서 1차 재회의 가장 강한 예측 인자다.
            JumpRule.USER_DUMPED, new Jump(new Band(65, 80), 0.75, true),
            JumpRule.USER_DUMPED_FAINT, new Jump(new Band(50, 68), 0.65, true),
            JumpRule.USER_DUMPED_NONE, new Jump(new Band(12, 24), 0.75, false),
            // 문은 다시 열렸지만 내용은 안부뿐 — 사유를 지울 만한 관측이 아니라 pull이 가장 낮다.
            JumpRule.PARTNER_RECONTACT, new Jump(new Band(30, 45), 0.55, true),
            JumpRule.PARTNER_HINT, new Jump(new Band(65, 80), 0.75, true),
            // 장벽이 사라진 것은 문이 열린 것보다 무겁고 돌아오겠다는 말보다 가볍다. 다만
            // 조건이 치워져도 감정이 남았는지는 별개라 사유를 절반쯤만 끌어당긴다.
            JumpRule.BARRIER_CLEARED, new Jump(new Band(42, 58), 0.60, true),
            // 같은 사유로 헤어지고도 매번 돌아온 관계 — 패턴이 입증됨.
            JumpRule.REPEAT_CYCLE, new Jump(new Band(58, 72), 0.75, true),
            JumpRule.PARTNER_CLOSED, new Jump(new Band(8, 18), 0.85, false),
            // 유일한 pull 1.0 — 구조적으로 닫힌 판이라 사유도 요인도 이걸 못 이긴다.
            JumpRule.PARTNER_MARRIED, new Jump(new Band(3, 8), 1.00, false)));

    // 요인별 조정 폭(매우X = 전체 폭, X = 절반). 선언 순서가 무게 순서와 일치한다.
    private static final Map<FactorName, Integer> WIDTHS = new EnumMap<>(Map.of(
            FactorName.PARTNER_SIGNAL, 10,
            FactorName.REPLACEMENT, 4,   // 유리(부재 확인)일 때만 이 값. 불리는 stage가 정한다
            FactorName.USER_CONDUCT, 8,
            FactorName.NOTICE_TONE, 6,
            FactorName.PARTNER_PATTERN, 5,
            FactorName.RELATIONSHIP_ASSET, 6,
            FactorName.CONTACT_PATH, 4));

    // 대체자 불리의 세분 폭.
    private static final int REPLACEMENT_SEEING = 6;
    private static final int REPLACEMENT_SETTLED = 12;

    // 요인이 대역 밖으로 나갈 수 있는 여유. 요인 폭의 합은 43인데 대역은 12~16이라, 대역에
    // 가둬두면 다섯 개쯤은 계산에 실리지도 못하고 클램프에 잘렸다(정착 하한 관통과 상대신호
    // 상한 관통은 그 손실을 특수 규칙으로 때우던 것이라 여기에 흡수하고 지웠다).
    private static final int HEADROOM = 10;

    // 유형은 하나다. 두 유형의 중간을 잡아주던 경계 유형(secondary)은 지웠다 — 경계 오판의
    // 낙차를 완충하려던 장치인데, 실측에서 발동 조건이 오판과 무관했다(발동 4건 중 절반이
    // primary 정답). 맞은 판을 가운데로 끌어내리는 대가가 틀린 판을 구해주는 값보다 컸고,
    // 무엇보다 오판의 티를 지워 루브릭을 고칠 단서를 가렸다. 경계는 완충이 아니라 유형별
    // 판별 관측으로 가른다.
    public int apply(BreakupType type, JumpRule jumpRule, List<AssessmentFactor> factors) {
        Jump jump = JUMPS.get(jumpRule == null ? JumpRule.NONE : jumpRule);
        Band base = BANDS.get(type);
        Band band = base == null ? jump.band() : pull(base, jump);
        int score = (band.lo() + band.hi()) / 2;
        boolean settled = false;
        for (AssessmentFactor factor : factors) {
            score += delta(factor);
            settled = settled || isSettled(factor);
        }
        // 결정적 점프(pull 1.0)는 요인에게 여유를 주지 않는다 — 그게 '결정적'의 뜻이다.
        int headroom = jump != null && jump.pull() >= 1.0 ? 0 : HEADROOM;
        // 하향 점프는 아래로만 칸막이를 세운다. 점프를 발동시킨 사실(차단, 정리 요구)을
        // 요인이 상대신호와 접점과 통보온도에서 각각 다시 세고, 그렇게 겹친 감점이 대역을
        // 관통해 절대 하한에 박혔다(200일 무다툼 올차단 판이 3% — 상대 결혼과 같은 자리).
        // 대역을 정한 사실이 대역 안에서 또 일하면 안 된다. 루브릭에 중복 계수 금지를
        // 명문화해봤으나 같은 사실을 다른 문장으로 적으면 모델은 중복으로 보지 않는다.
        // 위쪽은 그대로 연다 — 닫힌 판에서 진짜 유리한 관측이 나오면 그건 세야 한다.
        int floorRoom = jump != null && !jump.up() ? 0 : headroom;
        // 새 연인 '정착'만 하한을 아예 안 본다. 상대가 다른 사람과 자리를 잡았다는 것은
        // 이별 사유가 무엇이었든 그 위에서 다시 읽히는 사실이라, 대역이 받쳐주면 안 된다.
        int lo = settled ? ABS_MIN : band.lo() - floorRoom;
        score = Math.max(lo, Math.min(band.hi() + headroom, score));
        return Math.max(ABS_MIN, Math.min(ABS_MAX, score));
    }

    private Band pull(Band base, Jump jump) {
        if (jump == null) {
            return base;
        }
        return new Band(toward(base.lo(), jump.band().lo(), jump),
                toward(base.hi(), jump.band().hi(), jump));
    }

    private int toward(int from, int to, Jump jump) {
        int moved = (int) Math.round(from + jump.pull() * (to - from));
        return jump.up() ? Math.max(from, moved) : Math.min(from, moved);
    }

    private int delta(AssessmentFactor factor) {
        FactorLevel level = factor.getLevel();
        if (level == FactorLevel.NEUTRAL) {
            return 0;
        }
        if (factor.getName() == FactorName.REPLACEMENT && level.unfavorableSide()) {
            return isSettled(factor) ? -REPLACEMENT_SETTLED : -REPLACEMENT_SEEING;
        }
        int width = WIDTHS.get(factor.getName());
        int size = (level == FactorLevel.STRONG_FAVORABLE || level == FactorLevel.STRONG_UNFAVORABLE)
                ? width
                : Math.max(1, width / 2);
        return level.favorableSide() ? size : -size;
    }

    private boolean isSettled(AssessmentFactor factor) {
        return factor.getName() == FactorName.REPLACEMENT
                && factor.getLevel().unfavorableSide()
                && factor.getStage() == ReplacementStage.SETTLED;
    }

    // ── 판독(2호출) payload용 — 확률을 실제로 움직인 것의 서열 ──────────────────
    // "가능성을 높인/낮춘 신호"를 LLM에게 다시 고르게 하지 않는다. 숫자를 계산한 쪽이
    // 기여도를 제일 정확히 안다. delta는 내부 선별용이고 payload에는 싣지 않는다.

    public record Driver(String id, String direction, int rank, String source,
                         String evidence, String rationale, int magnitude) {
    }

    // 표시 등급 5단계. 화면 밴드 라벨(assessmentScale의 매우낮음~매우높음)과 같은 경계 —
    // 2호출이 등급 기준을 자체 발명하지 않게 백엔드가 확정한다.
    public String level(int probability) {
        if (probability >= 82) {
            return "VERY_HIGH";
        }
        if (probability >= 65) {
            return "HIGH";
        }
        if (probability >= 45) {
            return "MID";
        }
        return probability >= 25 ? "LOW" : "VERY_LOW";
    }

    // 방향별 상위 2개씩. 유형과 점프도 드라이버다 — 하단 유형(권태 등)은 그 자체가
    // 가장 큰 하락 요인인데 요인만 세면 "왜 낮은지"의 본체가 빠진다.
    public List<Driver> drivers(BreakupType type, JumpRule jumpRule, String typeEvidence,
                                List<AssessmentFactor> factors) {
        List<Driver> all = new ArrayList<>();
        if (type != null) {
            Band band = BANDS.get(type);
            int mid = (band.lo() + band.hi()) / 2;
            // 대역 중심이 50에서 먼 만큼 유형이 판을 움직인 크기로 본다.
            all.add(new Driver(null, mid >= 50 ? "UP" : "DOWN", 0, "TYPE",
                    typeEvidence == null ? "" : typeEvidence,
                    "이별 사유의 성격이 기본 가능성 구간을 정함", Math.abs(mid - 50)));
        }
        Jump jump = JUMPS.get(jumpRule == null ? JumpRule.NONE : jumpRule);
        if (jump != null) {
            // 점프는 사유보다 무겁다(설계) — 끌어당기는 힘(pull)을 크기로 환산한다.
            all.add(new Driver(null, jump.up() ? "UP" : "DOWN", 0, "JUMP", "",
                    "이별 후 상황이 기본 구간을 끌어당김", (int) Math.round(10 + jump.pull() * 10)));
        }
        for (AssessmentFactor factor : factors) {
            int delta = delta(factor);
            if (delta == 0) {
                continue;
            }
            all.add(new Driver(null, delta > 0 ? "UP" : "DOWN", 0, "FACTOR",
                    factor.getEvidence() == null ? "" : factor.getEvidence(),
                    factor.getRationale() == null ? "" : factor.getRationale(),
                    Math.abs(delta)));
        }
        List<Driver> ranked = new ArrayList<>();
        for (String direction : List.of("UP", "DOWN")) {
            List<Driver> side = all.stream()
                    .filter(d -> d.direction().equals(direction))
                    .sorted(Comparator.comparingInt(Driver::magnitude).reversed())
                    .limit(2)
                    .toList();
            for (int i = 0; i < side.size(); i++) {
                Driver d = side.get(i);
                ranked.add(new Driver("D" + String.format("%02d", ranked.size() + 1),
                        d.direction(), i + 1, d.source(), d.evidence(), d.rationale(),
                        d.magnitude()));
            }
        }
        return ranked;
    }

    // 표지(확률 판독)에서 쓸 대표 드라이버 하나 — 높음 계열이면 상승 1순위, 낮음 계열이면
    // 하락 1순위, MID면 크기가 가장 큰 것(판을 가장 잘 설명하는 축).
    // 같은 방향에서는 요인(FACTOR)을 유형/점프보다 우선한다 — 유형은 "구간"의 설명이지
    // 장면이 아니라서, 표지 이유로 번역되면 "충동적이라서 68%" 같은 밋밋한 문장이 된다.
    // 관찰된 장면(직전까지 관계를 붙잡던 발화 등)이 있으면 그쪽이 표지의 힘이다.
    public Driver primaryDriver(String level, List<Driver> drivers) {
        if (drivers.isEmpty()) {
            return null;
        }
        String want = level != null && level.endsWith("HIGH") ? "UP"
                : level != null && level.endsWith("LOW") ? "DOWN" : null;
        List<Driver> side = drivers.stream()
                .filter(d -> want == null || d.direction().equals(want))
                .toList();
        return side.stream()
                .filter(d -> "FACTOR".equals(d.source()))
                .max(Comparator.comparingInt(Driver::magnitude))
                .or(() -> side.stream().max(Comparator.comparingInt(Driver::magnitude)))
                .orElse(drivers.get(0));
    }

    private record Band(int lo, int hi) {
    }

    private record Jump(Band band, double pull, boolean up) {
    }
}

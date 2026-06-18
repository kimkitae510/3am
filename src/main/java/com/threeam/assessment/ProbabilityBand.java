package com.threeam.assessment;

// 확률을 유저가 읽는 등급으로 옮긴다. 화면의 assessmentScale.ts(bandLabel)와 같은 경계다 —
// 여기 값을 고치면 그쪽도 같이 고쳐야 한다. 두 벌인 이유는 화면이 서버 왕복 없이 게이지를
// 그려야 해서고, 어긋나면 같은 확률을 화면은 "낮음", 프롬프트는 "보통"이라 부른다.
//
// 서버에서 이 등급이 필요한 곳은 칩 추천기다. 확률 숫자만 주면 "왜 생각보다 낮게 나왔나요"와
// "왜 생각보다 높게 나왔나요" 중 무엇이 이 사연에 맞는지 추천기가 매번 자기 기준으로 가른다.
public final class ProbabilityBand {

    private ProbabilityBand() {
    }

    // 칩 필터용. "왜 생각보다 낮게 나왔나요"는 낮게 나온 판에서만, 높게는 그 반대에서만 뜬다.
    // 상담자에게 확률을 알려주고 고르게 하면 진단을 안 실어도 되는 턴에까지 숫자가 들어간다 —
    // 그래서 판정을 코드가 하고, 안 맞는 칩은 목록에 아예 안 보여준다.
    // 보통(45~64)은 둘 다 아니다. 그 자리는 DIAG_WHY가 맡는다.
    public static boolean isLow(int probability) {
        return probability < 45;
    }

    public static boolean isHigh(int probability) {
        return probability >= 65 && probability < 100;
    }

    public static String label(int probability) {
        if (probability >= 100) {
            return "상대의 재회 제안 유효";
        }
        if (probability < 25) {
            return "매우 낮음";
        }
        if (probability < 45) {
            return "낮음";
        }
        if (probability < 65) {
            return "보통";
        }
        if (probability < 82) {
            return "높음";
        }
        return "매우 높음";
    }
}

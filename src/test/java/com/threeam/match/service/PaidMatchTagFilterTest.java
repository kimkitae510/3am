package com.threeam.match.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 태그는 LLM이 쓰고 프롬프트가 1차로 거른다. 여기서 보는 건 그 다음 겹인 최후 방어선이다 —
// 프롬프트는 대부분 지켜지지만, 남의 카드에 적나라한 이름표가 박히는 건 한 번도 나면 안 되는 종류다.
class PaidMatchTagFilterTest {

    private final PaidMatchService service =
            new PaidMatchService(null, null, null, null, null, null, null, null);

    @SuppressWarnings("unchecked")
    private List<String> filter(List<String> tags) {
        return (List<String>) ReflectionTestUtils.invokeMethod(service, "safeTags", tags);
    }

    @Test
    @DisplayName("적나라한 태그는 걸러낸다 — 부분 문자열로 걸린다")
    void blocksHarshTags() {
        assertThat(filter(List.of("폭력", "가정폭력", "손찌검", "가스라이팅", "도박빚", "성적대상화")))
                .isEmpty();
    }

    @Test
    @DisplayName("평범한 겹침 태그는 그대로 통과한다")
    void keepsOrdinaryTags() {
        List<String> tags = List.of("새벽 카톡", "연락 뜸해짐", "회식 자리");

        assertThat(filter(tags)).containsExactlyElementsOf(tags);
    }

    // 카드 하나가 태그밭이 되면 무엇이 진짜 겹침인지 흐려진다(규칙 시절과 같은 상한).
    @Test
    @DisplayName("태그는 네 개까지만 싣는다")
    void capsTagCount() {
        assertThat(filter(List.of("하나", "둘", "셋", "넷", "다섯", "여섯")))
                .containsExactly("하나", "둘", "셋", "넷");
    }

    @Test
    @DisplayName("걸러낸 뒤 남은 것만 상한을 센다")
    void countsCapAfterFiltering() {
        assertThat(filter(List.of("폭력", "하나", "도박", "둘", "셋", "넷", "다섯")))
                .containsExactly("하나", "둘", "셋", "넷");
    }
}

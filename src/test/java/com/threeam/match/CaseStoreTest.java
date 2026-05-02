package com.threeam.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CaseStoreTest {

    // 실제 자산 파일로 로드를 검증한다. 파일은 gitignore라 없는 환경(새 클론)에선 건너뛴다 —
    // 실패로 두면 자산 없는 CI가 항상 빨갛게 된다.
    @Test
    @DisplayName("사례 파일을 읽어 배열 순서대로 id를 부여해 메모리에 올린다")
    void loadsCasesFromFile() {
        assumeTrue(Files.exists(Path.of("사례데이터.json")));

        CaseStore store = new CaseStore(new ObjectMapper());
        ReflectionTestUtils.setField(store, "caseFile", "사례데이터.json");
        ReflectionTestUtils.invokeMethod(store, "load");

        assertThat(store.all()).isNotEmpty();
        assertThat(store.all().get(0).getId()).isZero();
        assertThat(store.all().get(0).getStory()).isNotBlank();
        // 매칭의 1순위 신호라 서브태그가 비면 그 사례는 죽은 데이터다
        assertThat(store.all()).allSatisfy(c -> assertThat(c.subReasonList()).isNotEmpty());
    }
}

package com.threeam.assessment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.dto.RelationshipPsychology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelationshipPsychologyConverterTest {

    private final RelationshipPsychologyConverter converter = new RelationshipPsychologyConverter();

    @Test
    @DisplayName("JSON 왕복 - 저장하고 꺼내면 같은 값이다")
    void roundTrip() {
        RelationshipPsychology original = new RelationshipPsychology(
                new RelationshipPsychology.Attachment(
                        new RelationshipPsychology.Style("불안형", "중간"),
                        new RelationshipPsychology.Style("회피형", "높음"),
                        "멀어질수록 확인하려 했고 상대는 거리를 뒀음"),
                new RelationshipPsychology.PatternItem("추구-회피", "높음",
                        "확인할수록 물러나고 물러날수록 더 확인하는 반복"),
                new RelationshipPsychology.NeedConflict("연결감", "자율성",
                        "연결을 확인하는 행동이 상대의 혼자 시간을 침해함"));

        RelationshipPsychology restored =
                converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original));

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("null과 깨진 JSON은 null로 읽는다 — 옛 행 때문에 진단 조회가 500이 되면 안 된다")
    void brokenDataReadsAsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("{깨진 json")).isNull();
    }
}

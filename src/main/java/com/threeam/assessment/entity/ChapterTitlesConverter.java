package com.threeam.assessment.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

// 케이스별 장 제목(narrative/now/resolveRemain/reselect → 제목 문장)을 TEXT에 JSON 통짜로.
// 표시 전용이라 쿼리할 일이 없고, 장 구성이 바뀌면 키만 늘리면 된다(관계 심리 컨버터와 같은 사정).
// 읽기 실패는 null로 삼킨다 — 화면은 고정 제목으로 폴백한다.
@Slf4j
@Converter
public class ChapterTitlesConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.error("장 제목 직렬화 실패 — 이 판독은 고정 제목으로 표시된다", e);
            return null;
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.error("장 제목 역직렬화 실패 — 이 판독은 고정 제목으로 표시된다", e);
            return null;
        }
    }
}

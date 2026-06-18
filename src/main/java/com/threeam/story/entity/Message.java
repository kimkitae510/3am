package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
// 커서 페이지네이션("이 사연의 id < cursor 최신순 N개")을 인덱스 범위 스캔으로 처리하기 위한 복합 인덱스.
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_story_id", columnList = "story_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 이 말이 추천 질문 칩에서 왔다면 그 칩의 id(유저 메시지에만). 프롬프트를 찾으려고 두는 게
    // 아니라 기록이다 — 칩별 클릭률, 자유입력으로 새는 질문을 나중에 세려면 여기 있어야 한다.
    // 다만 재시도는 DB에서 프롬프트를 통째로 다시 조립하므로, 이 값이 없으면 재시도한 답변만
    // 전문 모듈 없이 나온다. 그래서 요청에만 실어 보내지 않고 행에 남긴다.
    @Column(name = "chip_id", length = 40)
    private String chipId;

    // 자유입력을 저가 판별이 이 칩으로 본 결과(유저 메시지에만). chip_id와 따로 두는 이유는
    // 측정이다 — chip_id는 "눌렀다"라서 클릭률의 분자고, 여기에 추론을 섞으면 그 수를 못 센다.
    // 둘 다 비어 있는 유저 메시지가 곧 "칩에 없는 질문"이라 40개를 늘릴 후보가 여기서 세어진다.
    @Column(name = "matched_chip_id", length = 40)
    private String matchedChipId;

    // 이 답변 밑에 붙일 추천 칩(답변 메시지에만). id와 함께 상담자가 유저 상황에 맞게 다시 쓴
    // 문장까지 담아야 새로고침 뒤에도 그 문장이 그대로 뜬다 — 직렬화는 ChipStore가 맡는다.
    @Column(name = "suggested_chips", columnDefinition = "TEXT")
    private String suggestedChips;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Message(Story story, MessageRole role, String content, String chipId) {
        this.story = story;
        this.role = role;
        this.content = content;
        this.chipId = chipId;
    }

    public static Message user(Story story, String content) {
        return user(story, content, null);
    }

    public static Message user(Story story, String content, String chipId) {
        return Message.builder().story(story).role(MessageRole.USER).content(content)
                .chipId(chipId).build();
    }

    public static Message assistant(Story story, String content) {
        return Message.builder().story(story).role(MessageRole.ASSISTANT).content(content).build();
    }

    // LLM 호출이 실패했을 때 답 대신 저장하는 말풍선. 폴링이 이걸 받고 정상 종료한다.
    // 페르소나가 하는 말이라 페르소나 문법을 따른다 — 하십시오체, 마침표 없이.
    // 여기 두는 이유: 화면의 재시도 버튼과 서버의 재시도 검사가 "이게 실패한 턴인가"를
    // 같은 기준으로 판정해야 한다. 문구가 두 군데로 갈리면 버튼만 뜨고 요청은 거절된다.
    public static final String FALLBACK_CONTENT =
            "죄송합니다, 지금 답을 정리하기가 어렵습니다\n조금 뒤에 다시 보내주시겠습니까?";

    public static Message fallback(Story story) {
        return assistant(story, FALLBACK_CONTENT);
    }

    // 이 말풍선이 '답을 못 받은 턴'인지. 문구를 바꾸면 그 전에 저장된 폴백은 재시도 대상에서
    // 빠지지만, 지난 대화의 버튼이 사라질 뿐이라 문제되지 않는다(컬럼을 새로 파지 않는 값).
    public boolean isFallback() {
        return role == MessageRole.ASSISTANT && FALLBACK_CONTENT.equals(content);
    }

    public void assignMatchedChip(String chipId) {
        this.matchedChipId = chipId;
    }

    // 이번 턴에 실을 전문 모듈을 정하는 칩. 누른 것이 먼저고, 없으면 판별한 것을 쓴다.
    public String effectiveChipId() {
        return chipId != null ? chipId : matchedChipId;
    }

    public void assignSuggestedChips(String encoded) {
        this.suggestedChips = encoded == null ? "" : encoded;
    }
}

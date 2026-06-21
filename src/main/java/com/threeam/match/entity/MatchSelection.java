package com.threeam.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 유료 사례 매칭의 결과. 진단 1건에 한 벌만 남는다(assessment_id UNIQUE).
//
// 저장하는 이유는 캐시가 아니라 재시도 수단이다. 무료 매칭은 요청마다 결정적으로 다시 계산해도
// 같은 답이 나왔는데, LLM이 끼면 새로고침마다 사례가 바뀐다 — 돈 낸 화면이 그러면 사고다.
// 그리고 저장이 끝난 뒤에 쿼터를 깎으므로, 중간에 서버가 죽으면 유저는 쿼터를 잃지 않고 다시 돌릴 수 있다.
//
// 재진단하면 새 assessment_id라 새로 뽑는다 — 옛 행은 그 시점의 판단으로 남는다.
@Entity
@Table(name = "match_selections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long assessmentId;

    // 방 삭제나 소유권 확인에 쓴다(매칭은 방의 파생물이다).
    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false)
    private Long userId;

    @Convert(converter = MatchPicksConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private MatchPick.Picks picks;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private MatchSelection(Long assessmentId, Long storyId, Long userId, MatchPick.Picks picks) {
        this.assessmentId = assessmentId;
        this.storyId = storyId;
        this.userId = userId;
        this.picks = picks;
    }

    public static MatchSelection of(Long assessmentId, Long storyId, Long userId,
                                    MatchPick.Picks picks) {
        return new MatchSelection(assessmentId, storyId, userId, picks);
    }
}

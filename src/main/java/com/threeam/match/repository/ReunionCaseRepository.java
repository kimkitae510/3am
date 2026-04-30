package com.threeam.match.repository;

import com.threeam.match.entity.ReunionCase;
import org.springframework.data.jpa.repository.JpaRepository;

// 전건을 읽어 메모리에서 점수를 매긴다. 사례가 수백 건 규모라 후보 축소가 아직 값어치가 없고,
// SQL로 유사도를 짜면 가중치를 조율할 때마다 쿼리를 고쳐야 한다.
// 수천 건대로 커지면 그때 reason으로 1차 필터를 걸면 된다.
public interface ReunionCaseRepository extends JpaRepository<ReunionCase, Long> {
}

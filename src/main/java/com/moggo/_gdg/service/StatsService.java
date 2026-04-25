package com.moggo._gdg.service;

import com.moggo._gdg.domain.User;
import com.moggo._gdg.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StatsService {

    private final UserRepository userRepository;
    private final CarbonEquivalents equivalents;

    public StatsService(UserRepository userRepository, CarbonEquivalents equivalents) {
        this.userRepository = userRepository;
        this.equivalents = equivalents;
    }

    @Transactional(readOnly = true)
    public GlobalCarbonStats globalCarbonStats() {
        List<User> users = userRepository.findAll();
        long userCount = users.size();
        if (userCount == 0) {
            return new GlobalCarbonStats(0, 0, 0d, 0d, 0d, equivalents.from(0d));
        }

        long activeUserCount = users.stream().filter(u -> u.getCarbonUsedG() > 0).count();
        double total = users.stream().mapToDouble(User::getCarbonUsedG).sum();
        double mean = total / userCount;
        double activeMean = activeUserCount == 0 ? 0 : total / activeUserCount;

        return new GlobalCarbonStats(
                userCount, activeUserCount, total, mean, activeMean, equivalents.from(total));
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            description = "전체 사용자의 누적 탄소 통계 스냅샷. 호출 시점의 users 테이블을 그대로 집계.")
    public record GlobalCarbonStats(
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "users 테이블의 총 row 수 (탄소 0인 사용자 포함)",
                    example = "42")
            long userCount,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "carbonUsedG > 0 인 사용자 수 (실제 메시지를 한 번이라도 보낸 사람)",
                    example = "31")
            long activeUserCount,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "전체 사용자의 누적 탄소 합 (gCO₂eq)",
                    example = "187.234")
            double totalCarbonG,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "전체 사용자 평균 탄소 (totalCarbonG / userCount)",
                    example = "4.458")
            double averageCarbonG,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "활성 사용자 평균 탄소 (totalCarbonG / activeUserCount). active=0 이면 0",
                    example = "6.040")
            double averageActiveCarbonG,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = """
                            `totalCarbonG` 를 입력으로 하여 일상 친숙 단위 3가지(북극 해빙 m²·자동차 km·
                            폰 충전 회)로 환산한 값. 각 필드별 수식과 출처는 Equivalents 스키마 참조.
                            **합산 금지** — 동일 탄소량을 서로 다른 단위로 표현한 것이라 더하면 의미 없음.
                            """)
            CarbonEquivalents.Equivalents equivalents
    ) {}
}

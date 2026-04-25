package com.moggo._gdg.controller;

import com.moggo._gdg.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@Tag(name = "Stats", description = "전체 사용자에 걸친 집계 통계. /impact 같은 글로벌 대시보드 용.")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/carbon")
    @Operation(
            summary = "전체 사용자 탄소 누적 통계",
            description = """
                    호출 시점 기준 users 테이블의 carbonUsedG 값을 한 번에 집계해 반환.

                    - **개인 식별 정보 미포함** — uid·이메일 등은 노출하지 않음. 분포·평균·중앙값만.
                    - **인증 필요** (글로벌 통계지만 공개 노출은 추후 결정. 인증 끄려면 SecurityConfig 의 PUBLIC_PATHS 에 추가).
                    - 데이터는 매 호출마다 새로 집계 (캐시 없음). 사용자 수 ~수만 명까지 충분히 빠름.
                    - 정확한 시계열 추이는 향후 별도 metrics(Prometheus) 또는 daily snapshot 테이블로 분리 예정.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "통계 스냅샷",
                    content = @Content(
                            schema = @Schema(implementation = StatsService.GlobalCarbonStats.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "userCount": 42,
                                      "activeUserCount": 31,
                                      "totalCarbonG": 187.234,
                                      "averageCarbonG": 4.458,
                                      "averageActiveCarbonG": 6.040,
                                      "equivalents": {
                                        "seaIceLossM2": 0.000562,
                                        "carKm": 1.325,
                                        "phoneCharges": 15.10
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Authorization 헤더 누락 또는 유효하지 않은 토큰"),
            @ApiResponse(responseCode = "403", description = "Firebase 검증 실패")
    })
    public StatsService.GlobalCarbonStats globalCarbon() {
        return statsService.globalCarbonStats();
    }
}

package com.moggo._gdg.controller;

import com.moggo._gdg.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "03.Me", description = "현재 Firebase 인증 사용자의 탄소 누적량 및 녹아내림 단계 조회")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "내 탄소/녹아내림 상태 조회",
            description = """
                    Firebase ID token 에서 추출한 uid 로 현재 사용자의 누적 탄소 및 녹아내림 상태를 반환한다.

                    **lazy provisioning**: uid 에 해당하는 row 가 DB 에 없으면 자동 생성 후 carbonUsedG=0 상태로 반환.
                    처음 로그인한 사용자도 이 endpoint 호출 한 번으로 users 테이블에 등록된다.

                    **프론트 사용 패턴**:
                    1. `AuthProvider.ready === true` 대기
                    2. 앱 초기 로드 시 1회 호출해 초기 상태 가져옴
                    3. 이후 `sendMessage` 응답의 `carbonState` 로 실시간 갱신 (재조회 불필요)
                    4. 페이지 새로고침 또는 다른 탭에서 탄소 동기화가 필요하면 다시 호출
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사용자 상태",
                    content = @Content(
                            schema = @Schema(implementation = UserService.MeResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "uid": "OhvZ8mBz1TYQRj2k4p",
                                      "carbonUsedG": 12.834,
                                      "stage": 2,
                                      "maxInputTokens": 2048,
                                      "meltingPercent": 42
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Authorization 헤더 누락 또는 유효하지 않은 토큰"),
            @ApiResponse(responseCode = "403", description = "토큰 있지만 Firebase 검증 실패 (만료/변조)")
    })
    public UserService.MeResponse me(
            @Parameter(hidden = true) @AuthenticationPrincipal String uid
    ) {
        return userService.toResponse(userService.getOrCreate(uid));
    }

    @PostMapping("/me/carbon/reset")
    @Operation(
            summary = "내 누적 탄소를 0 으로 초기화",
            description = """
                    누적 탄소(carbonUsedG) 를 0 으로 되돌린다. stage 와 meltingPercent 도 자연히 0 으로 리셋.

                    현재는 전액 초기화만 지원. 추후 "버튼 연타로 N g 차감" 같은 감산 방식으로 바뀔 수 있으므로
                    URL 은 `/me/carbon/reset` 으로 action-style 을 유지 (예: 추가될 `/me/carbon/reduce` 와 공존 가능).

                    대화 이력·메시지는 보존. 오직 User row 의 탄소 누적치만 리셋한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "초기화 후의 사용자 상태 (carbonUsedG=0, stage=0, meltingPercent=0)",
                    content = @Content(
                            schema = @Schema(implementation = UserService.MeResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "uid": "OhvZ8mBz1TYQRj2k4p",
                                      "carbonUsedG": 0.0,
                                      "stage": 0,
                                      "maxInputTokens": 8192,
                                      "meltingPercent": 0
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Authorization 헤더 누락 또는 유효하지 않은 토큰"),
            @ApiResponse(responseCode = "403", description = "토큰 있지만 Firebase 검증 실패")
    })
    public UserService.MeResponse resetCarbon(
            @Parameter(hidden = true) @AuthenticationPrincipal String uid
    ) {
        return userService.toResponse(userService.resetCarbon(uid));
    }
}

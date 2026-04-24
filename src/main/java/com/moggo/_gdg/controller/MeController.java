package com.moggo._gdg.controller;

import com.moggo._gdg.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Me", description = "현재 사용자 정보 및 탄소/녹아내림 상태")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user state",
            description = "Firebase 인증된 사용자의 uid, 누적 탄소량, 녹아내림 단계, 허용 입력 토큰 수 반환")
    public UserService.MeResponse me(@AuthenticationPrincipal String uid) {
        return userService.toResponse(userService.getOrCreate(uid));
    }
}

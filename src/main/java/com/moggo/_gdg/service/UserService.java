package com.moggo._gdg.service;

import com.moggo._gdg.domain.User;
import com.moggo._gdg.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final CarbonPolicy carbonPolicy;

    public UserService(UserRepository userRepository, CarbonPolicy carbonPolicy) {
        this.userRepository = userRepository;
        this.carbonPolicy = carbonPolicy;
    }

    @Transactional
    public User getOrCreate(String uid) {
        return userRepository.findById(uid).orElseGet(() -> userRepository.save(new User(uid)));
    }

    @Transactional
    public User resetCarbon(String uid) {
        User user = getOrCreate(uid);
        user.resetCarbon();
        return user;
    }

    public MeResponse toResponse(User user) {
        CarbonPolicy.MeltingState state = carbonPolicy.meltingStateFor(user.getCarbonUsedG());
        int percent = carbonPolicy.meltingPercent(user.getCarbonUsedG());
        return new MeResponse(
                user.getUid(),
                user.getCarbonUsedG(),
                state.stage(),
                state.maxInputTokens(),
                percent
        );
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            description = "현재 사용자의 탄소/녹아내림 상태")
    public record MeResponse(
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "Firebase UID",
                    example = "OhvZ8mBz1TYQRj2k4p...")
            String uid,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "누적 탄소 배출량 (gCO₂eq). 메시지 전송마다 누적",
                    example = "12.834")
            double carbonUsedG,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "녹아내림 단계 (0=정상, 5=완전 녹음=요청 거부). "
                            + "단계별 허용 input tokens 매핑은 API 전역 설명 참조.",
                    example = "2",
                    minimum = "0", maximum = "5")
            int stage,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "현재 stage 기준 허용되는 최대 prompt 토큰 수. "
                            + "이 값을 초과하는 prompt 는 서버에서 앞부분이 잘린 채 Gemini 에 전달됨.",
                    example = "2048")
            int maxInputTokens,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "현재 stage 내부의 진행률 (0~100). "
                            + "UI 녹아내림 강도 smooth 보간용. 100 에 도달하면 다음 stage 로 이동.",
                    example = "42",
                    minimum = "0", maximum = "100")
            int meltingPercent
    ) {}
}

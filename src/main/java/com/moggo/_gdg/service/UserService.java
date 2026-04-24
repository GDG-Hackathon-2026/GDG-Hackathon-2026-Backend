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

    public record MeResponse(
            String uid,
            double carbonUsedG,
            int stage,
            int maxInputTokens,
            int meltingPercent
    ) {}
}

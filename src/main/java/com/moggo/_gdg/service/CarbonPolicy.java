package com.moggo._gdg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CarbonPolicy {

    private final double gPer1kInput;
    private final double gPer1kOutput;

    public CarbonPolicy(@Value("${carbon.g-per-1k-input-tokens:0.015}") double gPer1kInput,
                        @Value("${carbon.g-per-1k-output-tokens:0.025}") double gPer1kOutput) {
        this.gPer1kInput = gPer1kInput;
        this.gPer1kOutput = gPer1kOutput;
    }

    /** 토큰 수 → 탄소(gCO2eq) 환산 */
    public double estimate(int promptTokens, int completionTokens) {
        return (promptTokens / 1000.0) * gPer1kInput
                + (completionTokens / 1000.0) * gPer1kOutput;
    }

    /** 누적 탄소량 → 녹아내림 단계(stage)와 허용 컨텍스트 크기 매핑. */
    public MeltingState meltingStateFor(double totalCarbonG) {
        if (totalCarbonG >= 500) return new MeltingState(5, 0);      // 완전 녹음: 거부
        if (totalCarbonG >= 200) return new MeltingState(4, 512);
        if (totalCarbonG >= 100) return new MeltingState(3, 1024);
        if (totalCarbonG >= 50)  return new MeltingState(2, 2048);
        if (totalCarbonG >= 20)  return new MeltingState(1, 4096);
        return new MeltingState(0, 8192);
    }

    /** 다음 단계까지 진행률 (현재 stage 내부에서 0~100). 프론트 UI 강도에 사용. */
    public int meltingPercent(double totalCarbonG) {
        double[] thresholds = {0, 20, 50, 100, 200, 500};
        MeltingState state = meltingStateFor(totalCarbonG);
        int stage = state.stage();
        if (stage >= thresholds.length - 1) return 100;
        double low = thresholds[stage];
        double high = thresholds[stage + 1];
        return (int) Math.max(0, Math.min(100, ((totalCarbonG - low) / (high - low)) * 100));
    }

    public record MeltingState(int stage, int maxInputTokens) {
        public boolean isRejected() { return stage >= 5; }
    }
}

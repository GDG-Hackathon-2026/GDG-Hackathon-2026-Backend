package com.moggo._gdg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 누적 탄소(g CO₂eq) 를 일상 친숙 단위로 환산.
 *
 * 환산 상수는 모두 application.yml 의 {@code carbon.equivalents.*} 로 외부화해
 * 팀이 새 수치로 결정하면 코드 변경 없이 교체 가능.
 *
 * <h3>환산 상수 출처 (2026-04 검증)</h3>
 * <ul>
 *   <li><b>sea-ice-m2-per-ton = 3.0</b> — Notz & Stroeve, "Observed Arctic
 *       sea-ice loss directly follows anthropogenic CO₂ emission",
 *       <i>Science</i> (2016). 1 metric ton CO₂ → 3 ± 0.3 m² 9월 북극 해빙
 *       영구 손실. <a href="https://www.science.org/doi/10.1126/science.aag2345">https://www.science.org/doi/10.1126/science.aag2345</a></li>
 *   <li><b>car-g-per-km = 141.3</b> — 환경부 "2016~2020년 자동차 온실가스
 *       관리제도 이행실적" (2021-06 공개). 2020년 국내 판매 승용·승합차 실제
 *       판매실적 가중평균 141.3 g/km. (인센티브 반영 사후 실적은 125.2 g/km
 *       이지만, 일상 비교에는 인센티브 미반영 평균이 더 적합)
 *       <a href="https://www.korea.kr/news/policyNewsView.do?newsId=148908381">https://www.korea.kr/news/policyNewsView.do?newsId=148908381</a></li>
 *   <li><b>phone-g-per-charge = 12.4</b> — US EPA Greenhouse Gas
 *       Equivalencies Calculator (2022 eGRID). 1.24 × 10⁻⁵ metric tons CO₂
 *       per smartphone charged = 12.4 g. 28.446 Wh/일 × 1,405.3 lbs CO₂/MWh
 *       미국 평균. 한국 그리드는 다소 더 탄소집약적이라 약간의 underestimate.
 *       <a href="https://www.epa.gov/energy/greenhouse-gas-equivalencies-calculator-calculations-and-references">https://www.epa.gov/energy/greenhouse-gas-equivalencies-calculator-calculations-and-references</a></li>
 * </ul>
 */
@Component
public class CarbonEquivalents {

    private final double seaIceM2PerTon;
    private final double carGPerKm;
    private final double phoneGPerCharge;

    public CarbonEquivalents(
            @Value("${carbon.equivalents.sea-ice-m2-per-ton:3.0}") double seaIceM2PerTon,
            @Value("${carbon.equivalents.car-g-per-km:141.3}") double carGPerKm,
            @Value("${carbon.equivalents.phone-g-per-charge:12.4}") double phoneGPerCharge) {
        this.seaIceM2PerTon = seaIceM2PerTon;
        this.carGPerKm = carGPerKm;
        this.phoneGPerCharge = phoneGPerCharge;
    }

    public Equivalents from(double carbonG) {
        if (carbonG <= 0) return new Equivalents(0d, 0d, 0d);
        double seaIce = (carbonG / 1_000_000.0) * seaIceM2PerTon;
        double carKm = carbonG / carGPerKm;
        double phones = carbonG / phoneGPerCharge;
        return new Equivalents(seaIce, carKm, phones);
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            description = """
                    탄소량(g CO₂eq)을 일상 친숙 단위로 환산한 값들. 입력 carbonG 하나에서 세 단위로 동시에 환산되며,
                    각 필드는 동일 탄소량의 서로 다른 표현일 뿐 합산하면 안 된다 (각각 독립 비교 지표).
                    환산 상수는 모두 application.yml `carbon.equivalents.*` 로 외부화되어 있어 논문/공식자료 갱신 시
                    재배포 한 번으로 교체 가능.
                    """)
    public record Equivalents(
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = """
                            **의미**: 입력 탄소량이 직접 야기한 9월 북극 해빙의 영구 손실 면적 (m²).

                            **수식**: `seaIceLossM2 = (carbonG / 1_000_000) × 3.0`
                            - `carbonG / 1_000_000`: 그램 → 메트릭톤 변환
                            - `× 3.0` (`sea-ice-m2-per-ton`): 1 ton CO₂ 당 손실 면적 m²

                            **출처**: Notz & Stroeve, "Observed Arctic sea-ice loss directly follows
                            anthropogenic CO₂ emission", *Science* (2016) —
                            [DOI:10.1126/science.aag2345](https://www.science.org/doi/10.1126/science.aag2345).
                            관측된 1 ton CO₂ 누적 배출당 3 ± 0.3 m² 해빙 영구 손실. 단위 작아 보이지만
                            사용자 누적이 kg 급으로 커지면 m² 단위로 의미 있는 수치가 됨.
                            """,
                    example = "0.000562")
            double seaIceLossM2,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = """
                            **의미**: 입력 탄소량이 평균 승용차 주행 시 환산되는 거리 (km).

                            **수식**: `carKm = carbonG / 141.3`
                            - 분모 `141.3` (`car-g-per-km`): 평균 승용차 1 km 주행당 배출 g CO₂

                            **출처**: 환경부 "2016~2020년 자동차 온실가스 관리제도 이행실적" —
                            [정책브리핑 보도자료](https://www.korea.kr/news/policyNewsView.do?newsId=148908381).
                            2020년 국내 판매 승용·승합차의 실제 판매실적 가중평균 (인센티브 미반영).
                            인센티브 반영 사후 실적은 125.2 g/km 이지만 일상 비교에는 실제 배출 평균이
                            더 직관적.
                            """,
                    example = "1.325")
            double carKm,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = """
                            **의미**: 입력 탄소량으로 가능한 스마트폰 풀 충전 횟수.

                            **수식**: `phoneCharges = carbonG / 12.4`
                            - 분모 `12.4` (`phone-g-per-charge`): 스마트폰 1회 풀 충전당 배출 g CO₂

                            **출처**: [US EPA Greenhouse Gas Equivalencies Calculator (2022 eGRID)](https://www.epa.gov/energy/greenhouse-gas-equivalencies-calculator-calculations-and-references).
                            1.24×10⁻⁵ metric tons CO₂ / charge = 12.4 g. 28.446 Wh/일 전력 소모 ×
                            1,405.3 lbs CO₂/MWh 미국 weighted-average 그리드 배출률 기반. 한국 그리드는
                            미국 평균보다 다소 더 탄소집약적이라 실제 한국 환경에선 약간 underestimate.
                            """,
                    example = "15.10")
            double phoneCharges
    ) {}
}

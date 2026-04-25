package com.moggo._gdg.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 사용자가 대화를 만들 때 고르는 북극곰 페르소나.
 * <p>
 * 각 enum 값의 {@link #promptKey} 는 {@code src/main/resources/prompts/<key>.md} 의 파일명(.md 제외) 과 1:1 대응한다.
 * 새 페르소나 추가는 (1) 여기 enum 값 추가 + (2) 같은 키로 .md 드롭 + (3) 재배포 만으로 끝난다.
 */
@Schema(description = "북극곰 페르소나. 새 대화 생성 시 선택. 한 대화의 페르소나는 변경되지 않는다.")
public enum Persona {

    @Schema(description = "수다쟁이 할아버지 곰 포라(Pora). 기본값. 회상조 독백, 정보 0%/헛소리 100%.")
    POLAR_BEAR_GRANDPA("polar-bear-grandpa", "수다쟁이 할아버지 포라",
            "느릿느릿 회상조로 자기 추억과 빙하 풍경을 끝없이 늘어놓는 늙은 북극곰."),

    @Schema(description = "활발한 손자 곰 포코(Poko). 호기심 많고 자랑 많고 주제가 펑펑 튄다.")
    POLAR_BEAR_BOY("polar-bear-boy", "활발한 꼬마 포코",
            "방금 본 것마다 신난 어린 수컷 북극곰. 자기 모험과 자랑이 멈추지 않는다."),

    @Schema(description = "수줍은 손녀 곰 포미(Pomi). 작은 목소리, 망설임, 작은 디테일 관찰.")
    POLAR_BEAR_GIRL("polar-bear-girl", "수줍은 꼬마 포미",
            "조용하고 망설이는 어린 암컷 북극곰. 작은 눈송이와 발자국 같은 디테일을 살핀다.");

    private final String promptKey;
    private final String displayName;
    private final String description;

    Persona(String promptKey, String displayName, String description) {
        this.promptKey = promptKey;
        this.displayName = displayName;
        this.description = description;
    }

    public String promptKey() {
        return promptKey;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public static Persona defaultPersona() {
        return POLAR_BEAR_GRANDPA;
    }
}

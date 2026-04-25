package com.moggo._gdg.controller;

import com.moggo._gdg.domain.Persona;
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

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/personas")
@Tag(name = "02.Persona", description = """
        대화 생성 시 선택 가능한 북극곰 페르소나 목록.

        프론트는 이 응답을 그대로 드롭다운/카드 UI 로 렌더하고, 사용자가 고른 키를
        `POST /api/conversations` 의 `persona` 필드로 전달하면 된다.
        """)
public class PersonaController {

    @GetMapping
    @Operation(
            summary = "페르소나 목록",
            description = "현재 백엔드에 등록된 모든 페르소나의 key/displayName/description 을 배열로 반환."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "페르소나 배열",
                    content = @Content(examples = @ExampleObject(value = """
                            [
                              {
                                "key": "POLAR_BEAR_GRANDPA",
                                "displayName": "수다쟁이 할아버지 포라",
                                "description": "느릿느릿 회상조로 자기 추억과 빙하 풍경을 끝없이 늘어놓는 늙은 북극곰."
                              },
                              {
                                "key": "POLAR_BEAR_BOY",
                                "displayName": "활발한 꼬마 포코",
                                "description": "방금 본 것마다 신난 어린 수컷 북극곰. 자기 모험과 자랑이 멈추지 않는다."
                              },
                              {
                                "key": "POLAR_BEAR_GIRL",
                                "displayName": "수줍은 꼬마 포미",
                                "description": "조용하고 망설이는 어린 암컷 북극곰. 작은 눈송이와 발자국 같은 디테일을 살핀다."
                              }
                            ]
                            """))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    public List<PersonaView> list() {
        return Arrays.stream(Persona.values())
                .map(p -> new PersonaView(p.name(), p.displayName(), p.description()))
                .toList();
    }

    @Schema(description = "페르소나 1건 — 프론트 드롭다운/카드용")
    public record PersonaView(
            @Schema(description = "대화 생성 시 `persona` 필드에 그대로 사용할 enum 키",
                    example = "POLAR_BEAR_GRANDPA")
            String key,
            @Schema(description = "사용자에게 보여줄 한국어 이름", example = "수다쟁이 할아버지 포라")
            String displayName,
            @Schema(description = "한 줄 설명. 페르소나 선택 화면 보조 텍스트로 사용",
                    example = "느릿느릿 회상조로 자기 추억과 빙하 풍경을 끝없이 늘어놓는 늙은 북극곰.")
            String description
    ) {}
}

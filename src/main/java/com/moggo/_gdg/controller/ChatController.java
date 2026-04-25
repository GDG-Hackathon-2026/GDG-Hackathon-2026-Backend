package com.moggo._gdg.controller;

import com.moggo._gdg.domain.Conversation;
import com.moggo._gdg.domain.Persona;
import com.moggo._gdg.service.ChatService;
import com.moggo._gdg.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@ConditionalOnExpression("'${gemini.project:}' != ''")
@Tag(name = "01.Chat",
        description = """
                LLM 채팅 도메인 — 대화 생성/목록/상세 + 메시지 전송 및 Gemini 호출.

                **핵심 동작** (메시지 전송 시):
                1. 사용자의 현재 녹아내림 단계 확인 → 요청 허용 여부 판단
                2. prompt 를 stage 별 `maxInputTokens` 에 맞춰 서버가 앞부분 truncate
                3. Gemini (Vertex AI) 호출 → 응답 + usage metadata
                4. 토큰 × 환산 계수로 탄소량 계산 후 users 테이블에 누적
                5. 새 녹아내림 상태 계산해 응답에 포함 (프론트는 이 값으로 UI 강도 업데이트)
                """)
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(
            summary = "새 대화 세션 생성",
            description = """
                    비어있는 대화 세션을 생성한다. 실제 메시지 송수신은 반환된 `id` 로 `POST /api/conversations/{id}/messages` 호출.

                    생성과 동시에 users 테이블에 사용자 row 가 없으면 lazy provisioning.

                    페르소나는 대화 단위가 아니라 **메시지 단위**로 지정한다. 메시지 전송 시 `persona` 필드를 통해
                    매 턴 다른 페르소나로 대화할 수 있다. 가용 목록은 `GET /api/personas` 참고.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "생성된 대화",
                    content = @Content(
                            schema = @Schema(implementation = Conversation.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 42,
                                      "userUid": "OhvZ8mBz1TYQRj2k4p",
                                      "title": "지구온난화 질문",
                                      "createdAt": "2026-04-24T08:30:00Z"
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    public Conversation create(
            @Parameter(hidden = true) @AuthenticationPrincipal String uid,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = false,
                    description = "title 생략 시 'New conversation' 으로 기본 설정",
                    content = @Content(examples = @ExampleObject(value = "{\"title\":\"지구온난화 질문\"}"))
            )
            @RequestBody(required = false) CreateRequest request) {
        String title = request == null ? null : request.title();
        return chatService.createConversation(uid, title);
    }

    @GetMapping
    @Operation(
            summary = "내 대화 목록",
            description = "현재 사용자의 모든 대화를 `createdAt` 내림차순(최신순) 으로 반환. 메시지는 포함하지 않음."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "대화 배열 (빈 배열 가능)"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    public List<Conversation> list(@Parameter(hidden = true) @AuthenticationPrincipal String uid) {
        return chatService.listConversations(uid);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "특정 대화 + 전체 메시지 조회",
            description = "대화 메타데이터와 해당 대화의 모든 메시지를 `createdAt` 오름차순으로 함께 반환."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "대화 + 메시지 배열"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404",
                    description = "해당 id 의 대화가 존재하지 않거나 현재 사용자 소유가 아님 (다른 사용자 조회 차단)")
    })
    public ChatService.ConversationView get(
            @Parameter(hidden = true) @AuthenticationPrincipal String uid,
            @Parameter(description = "대화 ID", example = "42") @PathVariable Long id) {
        return chatService.getConversation(uid, id);
    }

    @PostMapping("/{id}/messages")
    @Operation(
            summary = "메시지 전송 → Gemini 호출 → 탄소 누적",
            description = """
                    사용자 메시지를 저장하고, 현재 녹아내림 단계에 맞춰 잘라낸 prompt 를 Gemini 에 보낸 뒤
                    응답/사용량/탄소를 기록한다.

                    **동작 세부**:
                    - `content` 가 `maxInputTokens * 4` 문자(토큰 4배 근사) 초과 시 **뒷부분만** 유지하고 앞부분 제거
                      (대화 맥락에서 최신 내용이 중요하다는 가정). 이 경우 응답의 `truncated: true`.
                    - Gemini 출력 토큰도 `min(maxInputTokens, 2048)` 로 상한.
                    - 응답의 `carbonState` 가 다음 요청의 단계 결정에 바로 사용됨.

                    **거부 조건**: 누적 탄소 ≥ 500 gCO₂eq → 402 Payment Required.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Gemini 응답 + 갱신된 탄소 상태",
                    content = @Content(
                            schema = @Schema(implementation = ChatService.SendResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "assistantMessage": {
                                        "id": 1235,
                                        "content": "지구온난화는 주로 온실가스 배출로 인한…",
                                        "promptTokens": 42,
                                        "completionTokens": 128,
                                        "carbonG": 0.00384
                                      },
                                      "carbonState": {
                                        "totalCarbonG": 12.838,
                                        "stage": 2,
                                        "maxInputTokens": 2048,
                                        "meltingPercent": 42
                                      },
                                      "truncated": false
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400",
                    description = "content 가 비어있거나 공백, 또는 JSON body 파싱 실패 (code=VALIDATION)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "인증 토큰 누락 또는 검증 실패 (code=AUTH)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "402",
                    description = "녹아내림 stage 5 도달 — 탄소 예산 소진, 더 이상 LLM 호출 불가 (code=MELTED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "대화가 없거나 다른 사용자 소유 (code=NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502",
                    description = "Vertex AI 호출 실패 — 빌링 / 쿼터 / 일시적 오류. 재시도 권장 (code=GEMINI_UPSTREAM)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ChatService.SendResult send(
            @Parameter(hidden = true) @AuthenticationPrincipal String uid,
            @Parameter(description = "대화 ID", example = "42") @PathVariable Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "사용자 메시지 본문 + 이 턴에 적용할 페르소나 (생략 시 POLAR_BEAR_GRANDPA)",
                    content = @Content(examples = @ExampleObject(
                            value = "{\"content\":\"지구온난화에 대해 100자로 설명해줘\",\"persona\":\"POLAR_BEAR_BOY\"}"))
            )
            @RequestBody SendRequest request) {
        return chatService.sendMessage(uid, id, request.content(), request.persona());
    }

    @Schema(description = "대화 생성 요청 body")
    public record CreateRequest(
            @Schema(description = "대화 제목. 생략 가능, 생략 시 'New conversation'", example = "지구온난화 질문")
            String title
    ) {}

    @Schema(description = "메시지 전송 요청 body")
    public record SendRequest(
            @Schema(description = "사용자 메시지 본문. 공백 금지. 길면 서버에서 잘릴 수 있음",
                    example = "지구온난화에 대해 100자로 설명해줘",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String content,
            @Schema(description = "이 메시지에 답할 북극곰 페르소나. 생략 시 POLAR_BEAR_GRANDPA. "
                    + "메시지마다 다르게 지정 가능. 가용 값은 GET /api/personas 참고",
                    example = "POLAR_BEAR_BOY")
            Persona persona
    ) {}
}

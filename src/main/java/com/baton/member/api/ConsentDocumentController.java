package com.baton.member.api;

import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.member.application.ConsentDocumentView;
import com.baton.member.application.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "온보딩")
@RestController
@RequiredArgsConstructor
public class ConsentDocumentController {

    private final ConsentService consentService;
    private final Clock clock;

    /** CO-02/CO-03: kind별 현재 유효한 최신 버전(필수·선택 모두). 조회만으로 동의를 저장하지 않는다. */
    @Operation(summary = "동의할 약관 목록 조회", description = """
            COM-03 / CO-02, CO-03

            종류(kind)별로 현재 유효한 최신 버전을 필수·선택 모두 돌려준다. 조회(보기·닫기)만으로 동의를 저장하지 않는다.
            필수 여부는 required 값으로 판단한다.

            - 401 UNAUTHENTICATED: 인증 필요
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "약관 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/consent-documents")
    public ApiResponse<List<ConsentDocumentResponse>> documents() {
        return ApiResponse.ok(consentService.getCurrentDocuments().stream()
                .map(ConsentDocumentResponse::from)
                .toList(), clock);
    }

    /** @param bodyReference 약관 본문 URL(https 절대 경로). 프론트는 CO-03에서 이 URL의 본문을 표시한다. */
    public record ConsentDocumentResponse(
            @Schema(description = "약관 문서 id. 동의 저장 시 document_id로 보낸다", example = "1")
            long documentId,
            @Schema(description = "문서 종류(영문 대문자·_). 종류 목록은 미정", example = "TERMS_OF_SERVICE")
            String kind,
            @Schema(description = "문서 버전", example = "1")
            int version,
            @Schema(description = "문서 제목", example = "서비스 이용약관")
            String title,
            @Schema(description = "필수 동의 여부. true인 문서에 모두 동의해야 다음 단계로 간다", example = "true")
            boolean required,
            @Schema(description = "약관 본문 URL(https 절대 경로). CO-03에서 이 URL의 본문을 표시한다",
                    example = "https://example.com/baton/terms/terms-of-service/v1")
            String bodyReference
    ) {

        static ConsentDocumentResponse from(ConsentDocumentView view) {
            return new ConsentDocumentResponse(view.documentId(), view.kind(), view.version(), view.title(),
                    view.required(), view.bodyReference());
        }
    }
}

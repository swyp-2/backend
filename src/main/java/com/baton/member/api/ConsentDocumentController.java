package com.baton.member.api;

import com.baton.common.ApiResponse;
import com.baton.member.application.ConsentDocumentView;
import com.baton.member.application.ConsentService;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConsentDocumentController {

    private final ConsentService consentService;
    private final Clock clock;

    /** CO-02/CO-03: kind별 현재 유효한 최신 버전(필수·선택 모두). 조회만으로 동의를 저장하지 않는다. */
    @GetMapping("/consent-documents")
    public ApiResponse<List<ConsentDocumentResponse>> documents() {
        return ApiResponse.ok(consentService.getCurrentDocuments().stream()
                .map(ConsentDocumentResponse::from)
                .toList(), clock);
    }

    public record ConsentDocumentResponse(long documentId, String kind, int version, String title,
                                          boolean required, String bodyReference) {

        static ConsentDocumentResponse from(ConsentDocumentView view) {
            return new ConsentDocumentResponse(view.documentId(), view.kind(), view.version(), view.title(),
                    view.required(), view.bodyReference());
        }
    }
}

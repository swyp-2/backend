package com.baton.member.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.application.ConsentService;
import com.baton.member.domain.UserConsent;
import com.baton.member.domain.UserConsentRepository;
import com.baton.member.domain.UserRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ConsentApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired UserConsentRepository userConsentRepository;
    @Autowired Clock clock;

    OnboardingApi api;
    long termsId;
    long privacyId;
    long marketingId;

    @BeforeEach
    void setUp() throws Exception {
        api = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        for (JsonNode doc : api.currentDocuments(api.newUser())) {
            switch (doc.get("kind").asString()) {
                case "TERMS_OF_SERVICE" -> termsId = doc.get("document_id").asLong();
                case "PRIVACY_POLICY" -> privacyId = doc.get("document_id").asLong();
                case "MARKETING" -> marketingId = doc.get("document_id").asLong();
                default -> { }
            }
        }
    }

    @Test
    void 약관_조회는_필수와_선택_문서를_같은_형식으로_내려준다() throws Exception {
        long userId = api.newUser();

        api.documents(userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.kind == 'TERMS_OF_SERVICE')].required").value(true))
                .andExpect(jsonPath("$.data[?(@.kind == 'PRIVACY_POLICY')].required").value(true))
                .andExpect(jsonPath("$.data[?(@.kind == 'MARKETING')].required").value(false))
                .andExpect(jsonPath("$.data[?(@.kind == 'MARKETING')].title").value("(임시) 마케팅 정보 수신 동의"))
                .andExpect(jsonPath("$.data[0].document_id").isNumber())
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[?(@.kind == 'TERMS_OF_SERVICE')].body_reference")
                        .value("https://example.com/baton/terms/terms-of-service/v1"))
                .andExpect(jsonPath("$.meta.server_time").exists());
    }

    @Test
    void 약관을_보기만_하고_저장하지_않으면_동의_기록은_0건() throws Exception {
        long userId = api.newUser();

        api.documents(userId).andExpect(status().isOk());
        api.documents(userId).andExpect(status().isOk());

        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId)).isEmpty();
        api.me(userId).andExpect(jsonPath("$.data.onboarding_stage").value("CONSENT_REQUIRED"));
    }

    @Test
    void 필수_미동의는_422이고_해당_문서가_field_errors에_있다() throws Exception {
        long userId = api.newUser();

        api.agree(userId, List.of(
                        Map.of("document_id", termsId, "agreed", true),
                        Map.of("document_id", privacyId, "agreed", false),
                        Map.of("document_id", marketingId, "agreed", true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors.length()").value(1))
                .andExpect(jsonPath("$.error.field_errors[0].field").value(ConsentService.fieldOf(privacyId)));

        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId)).isEmpty();
        api.me(userId).andExpect(jsonPath("$.data.onboarding_stage").value("CONSENT_REQUIRED"));
    }

    @Test
    void 필수_문서가_요청에_빠지면_422() throws Exception {
        long userId = api.newUser();

        api.agree(userId, List.of(Map.of("document_id", marketingId, "agreed", true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors.length()").value(2))
                .andExpect(jsonPath("$.error.field_errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        ConsentService.fieldOf(termsId), ConsentService.fieldOf(privacyId))));

        api.agree(userId, List.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors.length()").value(2));
    }

    @Test
    void 선택_미동의는_통과하고_false로_기록된다() throws Exception {
        long userId = api.newUser();

        api.agree(userId, List.of(
                        Map.of("document_id", termsId, "agreed", true),
                        Map.of("document_id", privacyId, "agreed", true),
                        Map.of("document_id", marketingId, "agreed", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_stage").value("NAME_REQUIRED"));

        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId))
                .extracting(UserConsent::getDocumentId, UserConsent::isAgreed)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(termsId, true),
                        org.assertj.core.groups.Tuple.tuple(privacyId, true),
                        org.assertj.core.groups.Tuple.tuple(marketingId, false));
    }

    @Test
    void 요청에_없는_선택_문서는_false로_기록되고_시각은_서버_시각() throws Exception {
        long userId = api.newUser();
        Instant before = clock.instant();

        api.agree(userId, List.of(
                        Map.of("document_id", termsId, "agreed", true),
                        Map.of("document_id", privacyId, "agreed", true)))
                .andExpect(status().isOk());
        Instant after = clock.instant();

        List<UserConsent> consents = userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId);
        assertThat(consents).hasSize(3);
        UserConsent marketing = consents.stream().filter(c -> c.getDocumentId() == marketingId).findFirst()
                .orElseThrow();
        assertThat(marketing.isAgreed()).isFalse();
        assertThat(consents).allSatisfy(c -> assertThat(c.getAgreedAt()).isBetween(before, after));
        assertThat(consents).extracting(UserConsent::getAgreedAt).containsOnly(consents.get(0).getAgreedAt());
    }

    @Test
    void 클라이언트가_보낸_시각_필드는_무시한다() throws Exception {
        long userId = api.newUser();
        Instant before = clock.instant();

        api.agreeRaw(userId, """
                        {"agreements": [
                          {"document_id": %d, "agreed": true, "agreed_at": "2000-01-01T00:00:00Z"},
                          {"document_id": %d, "agreed": true}
                        ], "agreed_at": "2000-01-01T00:00:00Z"}
                        """.formatted(termsId, privacyId))
                .andExpect(status().isOk());

        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId))
                .allSatisfy(c -> assertThat(c.getAgreedAt()).isAfterOrEqualTo(before));
    }

    @Test
    void 유효하지_않은_문서_id는_422() throws Exception {
        long userId = api.newUser();

        api.agree(userId, List.of(
                        Map.of("document_id", termsId, "agreed", true),
                        Map.of("document_id", privacyId, "agreed", true),
                        Map.of("document_id", 987_654_321L, "agreed", true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value(ConsentService.fieldOf(987_654_321L)));
        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId)).isEmpty();
    }

    @Test
    void 같은_문서_id_중복은_422() throws Exception {
        long userId = api.newUser();

        api.agree(userId, List.of(
                        Map.of("document_id", termsId, "agreed", true),
                        Map.of("document_id", termsId, "agreed", false),
                        Map.of("document_id", privacyId, "agreed", true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value(ConsentService.fieldOf(termsId)));
        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId)).isEmpty();
    }

    @Test
    void 형식_오류는_422() throws Exception {
        long userId = api.newUser();

        api.agreeRaw(userId, "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("agreements"));
        api.agreeRaw(userId, "{\"agreements\": [{\"document_id\": %d}]}".formatted(termsId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("agreements[0].agreed"));
    }

    @Test
    void 동의_재요청은_409이고_기존_기록을_덮어쓰지_않는다() throws Exception {
        long userId = api.newUser();
        api.agree(userId, List.of(
                        Map.of("document_id", termsId, "agreed", true),
                        Map.of("document_id", privacyId, "agreed", true),
                        Map.of("document_id", marketingId, "agreed", false)))
                .andExpect(status().isOk());
        List<UserConsent> first = userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId);

        api.agree(userId, api.allAgreements(userId, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_STAGE_CONFLICT"))
                .andExpect(jsonPath("$.current_state").value("NAME_REQUIRED"));

        List<UserConsent> after = userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId);
        assertThat(after).hasSize(3);
        for (int i = 0; i < first.size(); i++) {
            assertThat(after.get(i).getId()).isEqualTo(first.get(i).getId());
            assertThat(after.get(i).isAgreed()).isEqualTo(first.get(i).isAgreed());
            assertThat(after.get(i).getAgreedAt()).isEqualTo(first.get(i).getAgreedAt());
        }
    }

    @Test
    void 같은_사용자의_동시_동의_요청은_한_번만_저장된다() throws Exception {
        long userId = api.newUser();
        List<Map<String, Object>> body = api.allAgreements(userId, true);
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    start.await();
                    return api.agree(userId, body).andReturn().getResponse().getStatus();
                };
                futures.add(executor.submit(task));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        assertThat(userConsentRepository.findByUserIdOrderByDocumentIdAsc(userId))
                .extracting(UserConsent::getDocumentId)
                .containsExactlyInAnyOrder(termsId, privacyId, marketingId);
    }
}

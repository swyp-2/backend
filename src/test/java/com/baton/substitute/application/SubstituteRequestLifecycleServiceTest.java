package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class SubstituteRequestLifecycleServiceTest {

    private final SubstituteRequestLifecycleService service = new SubstituteRequestLifecycleService();

    @Test
    void 처리할_요청이_없으면_종료한_요청도_없다() {
        assertThat(service.closeBySourceDeleted(1L, 2L)).isEmpty();
    }

    @Test
    void 처리할_요청이_없으면_활성_요청_요약도_없다() {
        assertThat(service.findActiveByShift(1L, 2L)).isEmpty();
    }

    @Test
    void 처리할_요청이_없으면_정리와_파기는_예외_없이_끝난다() {
        assertThatCode(() -> service.cleanupOnMembershipLeave(1L)).doesNotThrowAnyException();
        assertThatCode(() -> service.anonymizeFor(1L)).doesNotThrowAnyException();
    }
}

package com.baton.substitute.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RequestRecipientTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    void 지정_직후는_미응답() {
        RequestRecipient recipient = RequestRecipient.designate(1L, 2L);

        assertThat(recipient.getResponse()).isEqualTo(RecipientResponse.PENDING);
        assertThat(recipient.getRespondedAt()).isNull();
        assertThat(recipient.canAccept()).isTrue();
        assertThat(recipient.canDecline()).isTrue();
    }

    @Test
    void 거절한_대상은_재수락할_수_있다() {
        RequestRecipient recipient = RequestRecipient.designate(1L, 2L);
        recipient.decline(NOW);

        recipient.accept(LATER);

        assertThat(recipient.getResponse()).isEqualTo(RecipientResponse.ACCEPTED);
        assertThat(recipient.getRespondedAt()).isEqualTo(LATER);
    }

    @Test
    void 이미_거절한_대상은_다시_거절할_수_없다() {
        RequestRecipient recipient = RequestRecipient.designate(1L, 2L);
        recipient.decline(NOW);

        assertForbidden(() -> recipient.decline(LATER));
        assertThat(recipient.getRespondedAt()).isEqualTo(NOW);
    }

    @Test
    void 응답_불가_대상은_수락도_거절도_할_수_없다() {
        RequestRecipient recipient = RequestRecipient.designate(1L, 2L);
        recipient.markUnavailable(NOW);

        assertThat(recipient.getResponse()).isEqualTo(RecipientResponse.UNAVAILABLE);
        assertThat(recipient.getUnavailableAt()).isEqualTo(NOW);
        assertForbidden(() -> recipient.accept(LATER));
        assertForbidden(() -> recipient.decline(LATER));
    }

    @Test
    void 응답_불가_처리는_여러_번_해도_첫_시각을_유지한다() {
        RequestRecipient recipient = RequestRecipient.designate(1L, 2L);
        recipient.markUnavailable(NOW);
        recipient.markUnavailable(LATER);

        assertThat(recipient.getUnavailableAt()).isEqualTo(NOW);
    }

    private static void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}

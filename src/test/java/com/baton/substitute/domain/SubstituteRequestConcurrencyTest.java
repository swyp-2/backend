package com.baton.substitute.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import com.baton.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 근무에 진행 중 요청이 동시에 생성돼도 부분 유니크 인덱스로 한 건만 커밋되는지 확인.
 */
@IntegrationTest
class SubstituteRequestConcurrencyTest {

    private static final int THREADS = 8;
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(1));
    private static final Instant END = START.plus(Duration.ofHours(8));

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SubstituteRequestRepository requestRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void 같은_근무에_동시_생성하면_한_건만_성공한다() throws Exception {
        Long storeId = storeRepository.saveAndFlush(Store.open("동시성매장", NOW)).getId();
        Long userId = userRepository.saveAndFlush(User.signUp()).getId();
        Long requesterId = membershipRepository.saveAndFlush(
                Membership.join(storeId, userId, Role.CREW, "#112233", NOW)).getId();
        Long shiftId = shiftRepository.saveAndFlush(Shift.schedule(storeId, requesterId, START, END, NOW)).getId();

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        transactionTemplate.executeWithoutResult(status -> requestRepository.saveAndFlush(
                                SubstituteRequest.open(storeId, shiftId, requesterId, START, END, 0L, NOW)));
                        return true;
                    } catch (DataIntegrityViolationException e) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(requestRepository.findAll().stream()
                .filter(r -> r.getShiftId().equals(shiftId) && r.isActive())
                .count()).isEqualTo(1);
    }
}

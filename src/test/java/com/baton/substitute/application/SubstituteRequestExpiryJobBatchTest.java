package com.baton.substitute.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** 만료 job의 묶음 처리: id 키셋으로 다음 묶음을 가져오고, 한 건 실패가 나머지를 막지 않는다. */
class SubstituteRequestExpiryJobBatchTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");

    private final SubstituteRequestExpiryService service = mock(SubstituteRequestExpiryService.class);
    private final SubstituteRequestExpiryJob job =
            new SubstituteRequestExpiryJob(service, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 묶음이_가득_차면_마지막_id_다음부터_이어서_조회한다() {
        int size = SubstituteRequestExpiryJob.BATCH_SIZE;
        List<Long> first = LongStream.rangeClosed(1, size).boxed().toList();
        when(service.findExpirableIds(NOW, 0L, size)).thenReturn(first);
        when(service.findExpirableIds(NOW, (long) size, size)).thenReturn(List.of(500L));
        when(service.expire(anyLong())).thenReturn(true);

        job.expireStarted();

        verify(service, times(size + 1)).expire(anyLong());
        verify(service).expire(500L);
        verify(service, times(2)).findExpirableIds(eq(NOW), anyLong(), eq(size));
    }

    @Test
    void 한_건이_실패해도_나머지는_처리한다() {
        when(service.findExpirableIds(any(), eq(0L), eq(SubstituteRequestExpiryJob.BATCH_SIZE)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(service.expire(1L)).thenReturn(true);
        when(service.expire(2L)).thenThrow(new IllegalStateException("boom"));
        when(service.expire(3L)).thenReturn(true);

        job.expireStarted();

        verify(service).expire(3L);
        verify(service, times(1)).findExpirableIds(any(), anyLong(), eq(SubstituteRequestExpiryJob.BATCH_SIZE));
    }
}

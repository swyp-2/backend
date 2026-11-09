package com.baton.shift.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 미리보기 토큰에 넣는 해시. 순수 함수.
 * <ul>
 *   <li>입력 해시: 같은 계획이 나오는 입력은 같은 값(담당자·요일 순서 무관, 반복 미사용이면 반복 필드 무시)</li>
 *   <li>계획 해시: 새로 만들 건(중복 제외)만으로 계산. 미리보기 이후 중복 상태가 바뀌면 달라진다</li>
 * </ul>
 */
public final class ShiftBatchHashes {

    private ShiftBatchHashes() {
    }

    public static String inputHash(long storeId, ShiftBatchInput input) {
        StringBuilder sb = new StringBuilder("v1|store=").append(storeId);
        sb.append("|assignees=").append(sortedDistinct(input.assigneeMembershipIds()));
        sb.append("|starts=").append(input.startsAt());
        sb.append("|ends=").append(input.endsAt());
        if (input.repeatEnabled()) {
            ShiftBatchInput.Repeat r = input.repeat();
            sb.append("|repeat=").append(r.startDate()).append('~').append(r.endDate())
                    .append('/').append(sortedDistinct(r.weekdays()));
        } else {
            sb.append("|repeat=none");
        }
        return sha256(sb.toString());
    }

    /** toCreate는 계획 순서(starts_at, membership_id) 그대로. */
    public static String planHash(List<PlannedShift> toCreate) {
        String source = toCreate.stream()
                .map(s -> s.membershipId() + "|" + s.startsAt() + "|" + s.endsAt())
                .collect(Collectors.joining("\n", "v1\n", ""));
        return sha256(source);
    }

    private static <T extends Comparable<T>> String sortedDistinct(List<T> values) {
        if (values == null) {
            return "null";
        }
        return values.stream().filter(Objects::nonNull).distinct().sorted()
                .map(String::valueOf).collect(Collectors.joining(","));
    }

    static String sha256(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

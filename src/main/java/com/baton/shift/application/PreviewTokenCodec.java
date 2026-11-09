package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.config.ShiftBatchProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 근무 일괄 등록 미리보기 토큰. DB에 저장하지 않는 서명 문자열이다.
 * 형식: {@code base64url(payload) + "." + base64url(HMAC-SHA256(secret, base64url(payload)))},
 * payload: {@code v1|store_id|manager_membership_id|expires_epoch_seconds|input_hash|plan_hash}.
 * 프론트에는 불투명 문자열로만 안내한다(형식은 바뀔 수 있음).
 */
@Component
public class PreviewTokenCodec {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final ShiftBatchProperties properties;
    private final Clock clock;

    public PreviewTokenCodec(ShiftBatchProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = new SecretKeySpec(properties.previewSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public record Claims(long storeId, long managerMembershipId, String inputHash, String planHash) {
    }

    public String issue(Claims claims) {
        Instant expiresAt = clock.instant().plus(properties.previewTtl());
        String payload = String.join("|", VERSION, Long.toString(claims.storeId()),
                Long.toString(claims.managerMembershipId()), Long.toString(expiresAt.getEpochSecond()),
                claims.inputHash(), claims.planHash());
        String payloadPart = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return payloadPart + "." + ENCODER.encodeToString(sign(payloadPart));
    }

    /**
     * 서명·형식·만료를 확인하고 내용을 돌려준다. 어느 하나라도 맞지 않으면 PREVIEW_STALE.
     * 매장·매니저·해시 비교는 호출 측이 한다.
     */
    public Claims verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw stale();
        }
        // 디코딩한 바이트가 아니라 인코딩 문자열로 비교한다. base64 마지막 글자의 남는 비트만 바꾼 변조도 거부하기 위함
        byte[] expected = ENCODER.encodeToString(sign(parts[0])).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw stale();
        }
        String payload;
        try {
            payload = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw stale();
        }

        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6 || !VERSION.equals(fields[0])) {
            throw stale();
        }
        try {
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[3]));
            if (clock.instant().isAfter(expiresAt)) {
                throw stale();
            }
            return new Claims(Long.parseLong(fields[1]), Long.parseLong(fields[2]), fields[4], fields[5]);
        } catch (NumberFormatException e) {
            throw stale();
        }
    }

    private byte[] sign(String payloadPart) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException stale() {
        return new BusinessException(ErrorCode.PREVIEW_STALE);
    }
}

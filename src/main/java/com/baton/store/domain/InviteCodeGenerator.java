package com.baton.store.domain;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 매장 공통 초대 코드: 영문 대문자·숫자 8자리. 테스트에서 교체할 수 있도록 빈으로 둔다. */
@Component
public class InviteCodeGenerator {

    public static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}

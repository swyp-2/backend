package com.baton.store.domain;

import java.util.List;
import java.util.Map;

/**
 * 구성원 개인 색상 12색(대문자 hex, DB CHECK ^#[0-9A-F]{6}$). 매장의 ACTIVE 소속 중 가장 적게 쓰인 색을 고르고,
 * 동률이면 팔레트 순서를 따른다. 색상 중복은 허용한다(13번째 구성원부터는 반드시 겹친다).
 */
public final class PersonalColorPalette {

    public static final List<String> COLORS = List.of(
            "#E5484D", // red
            "#F76B15", // orange
            "#FFB224", // amber
            "#46A758", // green
            "#12A594", // teal
            "#05A2C2", // cyan
            "#0090FF", // blue
            "#3E63DD", // indigo
            "#8E4EC6", // purple
            "#D6409F", // pink
            "#A18072", // brown
            "#687076"  // slate
    );

    private PersonalColorPalette() {
    }

    /** @param usage 색상별 사용 수(팔레트 밖의 색은 무시) */
    public static String pick(Map<String, Long> usage) {
        String best = COLORS.getFirst();
        long bestCount = Long.MAX_VALUE;
        for (String color : COLORS) {
            long count = usage.getOrDefault(color, 0L);
            if (count < bestCount) {
                best = color;
                bestCount = count;
            }
        }
        return best;
    }
}

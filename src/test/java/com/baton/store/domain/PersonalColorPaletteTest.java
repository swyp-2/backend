package com.baton.store.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalColorPaletteTest {

    private static final java.util.List<String> C = PersonalColorPalette.COLORS;

    @Test
    void 팔레트는_서로_다른_대문자_hex_12색() {
        assertThat(C).hasSize(12);
        assertThat(new HashSet<>(C)).hasSize(12);
        assertThat(C).allMatch(c -> c.matches("^#[0-9A-F]{6}$"));
    }

    @Test
    void 아무도_없으면_첫_색() {
        assertThat(PersonalColorPalette.pick(Map.of())).isEqualTo(C.get(0));
    }

    @Test
    void 가장_적게_쓰인_색을_고르고_동률이면_팔레트_순서() {
        Map<String, Long> usage = new HashMap<>();
        usage.put(C.get(0), 1L);
        usage.put(C.get(1), 1L);
        assertThat(PersonalColorPalette.pick(usage)).isEqualTo(C.get(2));

        C.forEach(color -> usage.put(color, 2L));
        usage.put(C.get(9), 1L);
        usage.put(C.get(7), 1L);
        assertThat(PersonalColorPalette.pick(usage)).isEqualTo(C.get(7));
    }

    @Test
    void 모두_같은_수면_첫_색() {
        Map<String, Long> usage = new HashMap<>();
        C.forEach(color -> usage.put(color, 3L));
        assertThat(PersonalColorPalette.pick(usage)).isEqualTo(C.get(0));
    }
}

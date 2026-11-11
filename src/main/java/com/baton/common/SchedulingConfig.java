package com.baton.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러(멱등성 기록 정리, 대타 요청 만료, 이후 알림 정리) 활성화.
 *
 * <p>baton.scheduling.enabled=false면 끈다(기본 켜짐). test 프로필은 끄고 job 메서드를 직접 호출한다.
 * 켜 두면 실제 시각 기준으로 job이 돌면서 MutableClock을 옮기는 테스트의 데이터를 바꿀 수 있다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "baton.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}

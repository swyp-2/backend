package com.baton.support;

import org.springframework.test.web.servlet.ResultMatcher;

/** Spring 7에서 제거된 ResultMatcher.matchAll 대체. */
public final class Matchers {

    private Matchers() {
    }

    public static ResultMatcher all(ResultMatcher... matchers) {
        return result -> {
            for (ResultMatcher matcher : matchers) {
                matcher.match(result);
            }
        };
    }
}

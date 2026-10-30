package com.homeexpense.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    @Test
    void listeningOnThisComputerNeedsNoPassword() {
        assertThatCode(() -> SecurityConfig.requireLocalOnly("127.0.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> SecurityConfig.requireLocalOnly("localhost")).doesNotThrowAnyException();
        assertThatCode(() -> SecurityConfig.requireLocalOnly("")).doesNotThrowAnyException();
    }

    @Test
    void listeningOnANetworkAddressWithoutAPasswordRefusesToStart() {
        assertThatThrownBy(() -> SecurityConfig.requireLocalOnly("0.0.0.0"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HOME_EXPENSE_PASSWORD");
        assertThatThrownBy(() -> SecurityConfig.requireLocalOnly("192.168.1.20"))
                .isInstanceOf(IllegalStateException.class);
    }
}

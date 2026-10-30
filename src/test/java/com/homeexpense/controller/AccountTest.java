package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole account story on its own database: first visit, skip or create a password, sign in, change it, sign out
 * and turn it off. It has its own folder because it changes the password setting.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountTest {

    private static final Path DATA_DIR = tempDir();
    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("home-expense.data-dir", DATA_DIR::toString);
        registry.add("home-expense.first-run-setup", () -> "true");
        registry.add("home-expense.password", () -> "");
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("home-expense-account-test-").resolve("data");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void firstVisitCreatePasswordSignInChangeSignOutAndTurnOff() throws Exception {
        // 1. The first visit leads to the Welcome step, not to the program.
        mockMvc.perform(get("/")).andExpect(redirectedUrl("/account"));
        mockMvc.perform(get("/account")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome")))
                .andExpect(content().string(containsString("Create a password")))
                .andExpect(content().string(containsString("Skip for now")));
        mockMvc.perform(get("/login")).andExpect(redirectedUrl("/"));

        // 2. Too short, or two different passwords, are refused.
        mockMvc.perform(post("/account/password/new").param("password", "abc").param("confirm", "abc"))
                .andExpect(flash().attribute("error", containsString("at least 6")));
        mockMvc.perform(post("/account/password/new").param("password", "secret-1").param("confirm", "secret-2"))
                .andExpect(flash().attribute("error", containsString("not the same")));

        // 3. Creating a password signs you in straight away.
        var created = mockMvc.perform(post("/account/password/new").param("password", "secret-1").param("confirm", "secret-1"))
                .andExpect(redirectedUrl("/")).andReturn();
        MockHttpSession me = (MockHttpSession) created.getRequest().getSession(false);
        mockMvc.perform(get("/staff").session(me)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign out")))
                .andExpect(content().string(containsString("Account")));

        // 4. Everyone else is sent to the sign-in page, and the page shows no menu.
        mockMvc.perform(get("/staff")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Sign in")));

        // 5. The right password works for a new browser; a wrong one does not.
        MockHttpSession other = new MockHttpSession();
        String token = tokenFrom(mockMvc.perform(get("/login").session(other)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/login").session(other).param("username", "household").param("password", "nope").param("_csrf", token))
                .andExpect(redirectedUrl("/login?error"));
        other = new MockHttpSession();
        token = tokenFrom(mockMvc.perform(get("/login").session(other)).andReturn().getResponse().getContentAsString());
        var signedIn = mockMvc.perform(post("/login").session(other).param("username", "household")
                .param("password", "secret-1").param("_csrf", token)).andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession active = (MockHttpSession) signedIn.getRequest().getSession(false);
        mockMvc.perform(get("/account").session(active)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Change password")))
                .andExpect(content().string(containsString("Turn the password off")));

        // 6. Changing the password needs the current one.
        String formToken = tokenFrom(mockMvc.perform(get("/account").session(active)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/account/password/change").session(active).param("_csrf", formToken)
                        .param("current", "wrong").param("password", "secret-3").param("confirm", "secret-3"))
                .andExpect(flash().attribute("error", containsString("current password is not right")));
        mockMvc.perform(post("/account/password/change").session(active).param("_csrf", formToken)
                        .param("current", "secret-1").param("password", "secret-3").param("confirm", "secret-3"))
                .andExpect(flash().attribute("ok", containsString("Password changed")));

        // 7. Sign out ends the session.
        mockMvc.perform(post("/logout").session(active).param("_csrf", formToken)).andExpect(redirectedUrl("/login?bye"));
        mockMvc.perform(get("/staff").session(active)).andExpect(status().is3xxRedirection());

        // 8. The old password no longer works; the new one does. Then turn the password off.
        MockHttpSession again = new MockHttpSession();
        token = tokenFrom(mockMvc.perform(get("/login").session(again)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/login").session(again).param("username", "household").param("password", "secret-1").param("_csrf", token))
                .andExpect(redirectedUrl("/login?error"));
        again = new MockHttpSession();
        token = tokenFrom(mockMvc.perform(get("/login").session(again)).andReturn().getResponse().getContentAsString());
        var back = mockMvc.perform(post("/login").session(again).param("username", "household").param("password", "secret-3")
                .param("_csrf", token)).andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession last = (MockHttpSession) back.getRequest().getSession(false);
        String offToken = tokenFrom(mockMvc.perform(get("/account").session(last)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/account/password/off").session(last).param("_csrf", offToken).param("current", "wrong"))
                .andExpect(flash().attribute("error", containsString("current password is not right")));
        mockMvc.perform(post("/account/password/off").session(last).param("_csrf", offToken).param("current", "secret-3"))
                .andExpect(flash().attribute("ok", containsString("turned off")));
        assertThat(mockMvc.perform(get("/staff")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private static String tokenFrom(String html) {
        Matcher m = TOKEN.matcher(html);
        assertThat(m.find()).as("the page must carry a form token").isTrue();
        return m.group(1);
    }
}

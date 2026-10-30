package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.homeexpense.SqliteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The household password: everything is locked until the right password is given. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "home-expense.password=correct-horse")
class LoginTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Test
    void everyPageNeedsSigningInExceptTheSignInPageAndHealthCheck() throws Exception {
        for (String path : new String[]{"/", "/staff", "/pay", "/backup"}) {
            mockMvc.perform(get(path)).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
        mockMvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Sign in")));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
    }

    @Test
    void aPostWithoutSigningInOrTheFormTokenIsRefused() throws Exception {
        mockMvc.perform(post("/staff").param("name", "Intruder").param("typeId", "1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/backup/download")).andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordRightPasswordSignOutAndLockoutInOrder() throws Exception {
        // wrong password
        MockHttpSession session = new MockHttpSession();
        String token = tokenFrom(session);
        mockMvc.perform(post("/login").session(session).param("username", "household").param("password", "nope").param("_csrf", token))
                .andExpect(redirectedUrl("/login?error"));

        // right password
        session = new MockHttpSession();
        token = tokenFrom(session);
        var signedIn = mockMvc.perform(post("/login").session(session).param("username", "household")
                        .param("password", "correct-horse").param("_csrf", token))
                .andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession active = (MockHttpSession) signedIn.getRequest().getSession(false);
        mockMvc.perform(get("/staff").session(active)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign out")));

        // sign out
        String pageToken = tokenFromPage(mockMvc.perform(get("/staff").session(active)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/logout").session(active).param("_csrf", pageToken)).andExpect(redirectedUrl("/login?bye"));
        mockMvc.perform(get("/staff").session(active)).andExpect(status().is3xxRedirection());

        // five wrong tries lock sign-in, even for the right password
        for (int i = 0; i < 5; i++) {
            MockHttpSession s = new MockHttpSession();
            mockMvc.perform(post("/login").session(s).param("username", "household").param("password", "bad" + i)
                    .param("_csrf", tokenFrom(s)));
        }
        MockHttpSession locked = new MockHttpSession();
        mockMvc.perform(post("/login").session(locked).param("username", "household").param("password", "correct-horse")
                        .param("_csrf", tokenFrom(locked)))
                .andExpect(redirectedUrl("/login?locked"));
    }

    private String tokenFrom(MockHttpSession session) throws Exception {
        return tokenFromPage(mockMvc.perform(get("/login").session(session)).andReturn().getResponse().getContentAsString());
    }

    private static String tokenFromPage(String html) {
        Matcher m = TOKEN.matcher(html);
        assertThat(m.find()).as("the page must carry a form token").isTrue();
        return m.group(1);
    }
}

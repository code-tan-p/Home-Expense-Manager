package com.homeexpense.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.homeexpense.service.MailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Many people, each with a household of their own: sign-up, sign-in, recovery, lock-out, deleting an account, and above all
 * that one household never sees another's records. It runs on its own data folder with no fixed household.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignUpTest {

    private static final Path DATA_DIR = tempDir();
    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("home-expense.data-dir", DATA_DIR::toString);
        registry.add("home-expense.fixed-household", () -> "");
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("home-expense-signup-test-").resolve("data");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HouseholdDatabases databases;
    @MockitoBean
    private MailService mail;

    /** A person who is signed in, with the recovery code they were shown. */
    private record Person(String email, String password, MockHttpSession session) {
    }

    /** Each call comes from a new address, so the sign-up limit per address does not get in the way. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private Person signUp(String name) throws Exception {
        String email = name.toLowerCase() + NEXT.getAndIncrement() + "@example.com";
        MockHttpSession session = new MockHttpSession();
        String token = token(mockMvc.perform(get("/signup").session(session)).andReturn());
        MvcResult done = mockMvc.perform(post("/signup").session(session).with(from("10.1.0." + NEXT.get()))
                        .param("name", name).param("email", email).param("password", "Secret-Pw-1!").param("confirm", "Secret-Pw-1!")
                        .param("_csrf", token).header("Referer", "http://localhost/signup")).andReturn();
        assertThat(done.getFlashMap().get("error")).as("sign-up error").isNull();
        assertThat(done.getResponse().getRedirectedUrl()).isEqualTo("/");
        MockHttpSession active = (MockHttpSession) done.getRequest().getSession(false);
        assertThat(done.getFlashMap().get("ok").toString()).contains("Welcome, " + name);
        mockMvc.perform(get("/").session(active)).andExpect(status().isOk());
        return new Person(email, "Secret-Pw-1!", active);
    }

    private String token(MvcResult page) throws Exception {
        Matcher m = TOKEN.matcher(page.getResponse().getContentAsString());
        assertThat(m.find()).as("the page must carry a form token").isTrue();
        return m.group(1);
    }

    private String token(Person who) throws Exception {
        return token(mockMvc.perform(get("/account").session(who.session())).andReturn());
    }

    private void addWorker(Person who, String name) throws Exception {
        mockMvc.perform(post("/staff").session(who.session()).param("_csrf", token(who)).param("name", name)
                .param("typeId", "1").param("start", "2026-04-01").param("pay", "MONTHLY_SALARY").param("rate", "3000")
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"));
    }

    @Test
    void visitorsAreSentToSignInAndCannotPostWithoutTheFormToken() throws Exception {
        for (String path : new String[] {"/", "/staff", "/pay", "/backup", "/calendar", "/account"}) {
            mockMvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        mockMvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Create your free account")));
        mockMvc.perform(get("/signup")).andExpect(status().isOk());
        mockMvc.perform(get("/forgot")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(post("/staff").param("name", "Intruder").param("typeId", "1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/signup").param("name", "X").param("email", "x@example.com")
                .param("password", "Secret-Pw-1!").param("confirm", "Secret-Pw-1!")).andExpect(status().isForbidden());
    }

    @Test
    void signUpChecksTheFormAndRefusesAnEmailThatIsAlreadyUsed() throws Exception {
        MockHttpSession s = new MockHttpSession();
        String token = token(mockMvc.perform(get("/signup").session(s)).andReturn());
        mockMvc.perform(post("/signup").session(s).param("_csrf", token).param("name", "Ann").param("email", "not-an-email")
                .param("password", "Secret-Pw-1!").param("confirm", "Secret-Pw-1!"))
                .andExpect(flash().attribute("error", containsString("valid email")));
        mockMvc.perform(post("/signup").session(s).param("_csrf", token).param("name", "Ann").param("email", "ann@example.com")
                .param("password", "short").param("confirm", "short"))
                .andExpect(flash().attribute("error", containsString("at least 9")));
        mockMvc.perform(post("/signup").session(s).param("_csrf", token).param("name", "Ann").param("email", "ann@example.com")
                .param("password", "alllowercase1").param("confirm", "alllowercase1"))
                .andExpect(flash().attribute("error", containsString("uppercase letter")));
        mockMvc.perform(post("/signup").session(s).param("_csrf", token).param("name", "Ann").param("email", "ann@example.com")
                .param("password", "NoSpecial123").param("confirm", "NoSpecial123"))
                .andExpect(flash().attribute("error", containsString("special character")));
        mockMvc.perform(post("/signup").session(s).param("_csrf", token).param("name", "Ann").param("email", "ann@example.com")
                .param("password", "Secret-Pw-1!").param("confirm", "Secret-Pw-2!"))
                .andExpect(flash().attribute("error", containsString("not the same")));
        mockMvc.perform(post("/signup").session(s).param("_csrf", token).param("name", "Ann").param("email", "ann@example.com")
                .param("password", "x".repeat(80)).param("confirm", "x".repeat(80)))
                .andExpect(flash().attribute("error", containsString("too long")));

        Person first = signUp("Same");
        MockHttpSession again = new MockHttpSession();
        String t2 = token(mockMvc.perform(get("/signup").session(again)).andReturn());
        mockMvc.perform(post("/signup").session(again).param("_csrf", t2).param("name", "Other").param("email", first.email().toUpperCase())
                        .param("password", "Secret-Pw-1!").param("confirm", "Secret-Pw-1!"))
                .andExpect(flash().attribute("error", containsString("already has an account")));
    }

    @Test
    void oneHouseholdNeverSeesAnothersRecords() throws Exception {
        Person anna = signUp("Anna");
        Person ben = signUp("Ben");
        addWorker(anna, "Annas Maid");
        addWorker(ben, "Bens Cook");

        mockMvc.perform(get("/staff").session(anna.session())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Annas Maid"))).andExpect(content().string(not(containsString("Bens Cook"))));
        mockMvc.perform(get("/staff").session(ben.session())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Bens Cook"))).andExpect(content().string(not(containsString("Annas Maid"))));
        mockMvc.perform(get("/calendar").param("month", "2026-04").session(ben.session())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Annas Maid"))));
        mockMvc.perform(get("/pay").param("month", "2026-04").session(ben.session())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Annas Maid"))));

        // Each household has its own file.
        assertThat(databases.file(userHousehold(anna))).isNotEqualTo(databases.file(userHousehold(ben)));
    }

    private long userHousehold(Person who) {
        return ((HouseholdUser) ((org.springframework.security.core.context.SecurityContext) who.session()
                .getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication().getPrincipal()).getHouseholdId();
    }

    @Test
    void signInChangePasswordAndSignOut() throws Exception {
        Person cara = signUp("Cara");
        MockHttpSession fresh = new MockHttpSession();
        String t = token(mockMvc.perform(get("/login").session(fresh)).andReturn());
        mockMvc.perform(post("/login").session(fresh).param("username", cara.email()).param("password", "wrong-password").param("_csrf", t))
                .andExpect(redirectedUrl("/login?error"));
        fresh = new MockHttpSession();
        t = token(mockMvc.perform(get("/login").session(fresh)).andReturn());
        MockHttpSession active = (MockHttpSession) mockMvc.perform(post("/login").session(fresh).param("username", cara.email())
                .param("password", cara.password()).param("_csrf", t)).andExpect(status().is3xxRedirection()).andReturn()
                .getRequest().getSession(false);
        mockMvc.perform(get("/staff").session(active)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign out"))).andExpect(content().string(containsString("Cara")));

        Person me = new Person(cara.email(), cara.password(), active);
        String csrf = token(me);
        mockMvc.perform(get("/account").session(active)).andExpect(content().string(not(containsString("Change password"))))
                .andExpect(content().string(containsString("Forgot password")));
        mockMvc.perform(post("/account/password/change").session(active).param("_csrf", csrf).param("current", cara.password())
                .param("password", "Newer-Pw-22!").param("confirm", "Newer-Pw-22!")).andExpect(status().is4xxClientError());
        // exactly one Sign out on a page: the one in the header
        String page = mockMvc.perform(get("/account").session(active)).andReturn().getResponse().getContentAsString();
        assertThat(page.split("Sign out", -1).length - 1).isEqualTo(1);
        mockMvc.perform(post("/logout").session(active).param("_csrf", csrf)).andExpect(redirectedUrl("/login?bye"));
        mockMvc.perform(get("/staff").session(active)).andExpect(status().is3xxRedirection());
    }

    @Test
    void fiveWrongSignInsLockThatEmailEvenForTheRightPassword() throws Exception {
        Person eli = signUp("Eli");
        for (int i = 0; i < 5; i++) {
            MockHttpSession s = new MockHttpSession();
            mockMvc.perform(post("/login").session(s).with(from("10.3.0." + i)).param("username", eli.email()).param("password", "bad-" + i)
                    .param("_csrf", token(mockMvc.perform(get("/login").session(s)).andReturn())));
        }
        MockHttpSession s = new MockHttpSession();
        mockMvc.perform(post("/login").session(s).with(from("10.3.0.99")).param("username", eli.email()).param("password", eli.password())
                        .param("_csrf", token(mockMvc.perform(get("/login").session(s)).andReturn())))
                .andExpect(redirectedUrl("/login?locked"));
    }

    @Test
    void backupAndRestoreOnlyTouchTheSignedInHousehold() throws Exception {
        Person fay = signUp("Fay");
        Person gus = signUp("Gus");
        addWorker(fay, "Fays Maid");
        addWorker(gus, "Guss Driver");

        byte[] fayBackup = mockMvc.perform(post("/backup/download").session(fay.session()).param("_csrf", token(fay)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(fayBackup, StandardCharsets.ISO_8859_1)).contains("Fays Maid").doesNotContain("Guss Driver");

        addWorker(fay, "Fays Extra");
        mockMvc.perform(multipart("/backup/restore").file(new MockMultipartFile("file", "b.db", "application/octet-stream", fayBackup))
                        .session(fay.session()).param("_csrf", token(fay)))
                .andExpect(flash().attribute("ok", containsString("restored")));
        mockMvc.perform(get("/staff").session(fay.session())).andExpect(content().string(containsString("Fays Maid")))
                .andExpect(content().string(not(containsString("<div class=\"name\">Fays Extra")))); // the page may still show the old "added" notice
        mockMvc.perform(get("/staff").session(gus.session())).andExpect(content().string(containsString("Guss Driver")));

        mockMvc.perform(multipart("/backup/restore").file(new MockMultipartFile("file", "x.db", "application/octet-stream", "junk".getBytes()))
                        .session(gus.session()).param("_csrf", token(gus)))
                .andExpect(flash().attribute("error", containsString("not a Home Expense Manager backup")));
        mockMvc.perform(get("/staff").session(gus.session())).andExpect(content().string(containsString("Guss Driver")));
    }

    @Test
    void deletingAnAccountRemovesItsRecordsAndSignIn() throws Exception {
        Person hal = signUp("Hal");
        addWorker(hal, "Hals Maid");
        long household = userHousehold(hal);
        assertThat(databases.exists(household)).isTrue();
        String csrf = token(hal);
        mockMvc.perform(post("/account/delete").session(hal.session()).param("_csrf", csrf).param("password", hal.password()))
                .andExpect(flash().attribute("error", containsString("Type the word DELETE")));
        mockMvc.perform(post("/account/delete").session(hal.session()).param("_csrf", csrf).param("password", hal.password()).param("typed", "delete"))
                .andExpect(flash().attribute("error", containsString("Type the word DELETE")));
        mockMvc.perform(post("/account/delete").session(hal.session()).param("_csrf", csrf).param("password", "Wrong-One-1!").param("typed", "DELETE"))
                .andExpect(flash().attribute("error", containsString("not right")));
        assertThat(databases.exists(household)).isTrue();
        mockMvc.perform(post("/account/delete").session(hal.session()).param("_csrf", csrf).param("password", hal.password()).param("typed", "DELETE"))
                .andExpect(redirectedUrl("/login?deleted"));
        assertThat(databases.exists(household)).isFalse();
        MockHttpSession s = new MockHttpSession();
        mockMvc.perform(post("/login").session(s).with(from("10.4.0.1")).param("username", hal.email()).param("password", hal.password())
                        .param("_csrf", token(mockMvc.perform(get("/login").session(s)).andReturn())))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void emailedLinkResetsThePasswordOnceAndNeverRevealsWhoHasAnAccount() throws Exception {
        org.mockito.Mockito.when(mail.enabled()).thenReturn(true);
        Person ida = signUp("Ida");
        MockHttpSession s = new MockHttpSession();
        String t = token(mockMvc.perform(get("/forgot").session(s)).andReturn());
        mockMvc.perform(get("/forgot").session(s)).andExpect(content().string(containsString("Send me the link")));

        // an unknown email gets the same answer and nothing is sent
        mockMvc.perform(post("/forgot").session(s).with(from("10.5.0.1")).param("_csrf", t).param("email", "nobody@example.com"))
                .andExpect(flash().attribute("ok", containsString("If that email has an account")));
        org.mockito.Mockito.verify(mail, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(post("/forgot").session(s).with(from("10.5.0.2")).param("_csrf", t).param("email", ida.email()))
                .andExpect(flash().attribute("ok", containsString("If that email has an account")));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mail).send(org.mockito.ArgumentMatchers.eq(ida.email()), org.mockito.ArgumentMatchers.anyString(), text.capture());
        Matcher m = Pattern.compile("/reset\\?token=([A-Za-z0-9_-]+)").matcher(text.getValue());
        assertThat(m.find()).isTrue();
        String token = m.group(1);

        // a wrong token shows the "not valid" page; the right one shows the form with the strength helpers
        mockMvc.perform(get("/reset").param("token", "not-a-real-token")).andExpect(content().string(containsString("not valid any more")));
        String form = mockMvc.perform(get("/reset").param("token", token).session(s)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Save new password"))).andExpect(content().string(containsString("data-new")))
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("Suggest a strong password");

        // a weak password is refused and the link still works
        mockMvc.perform(post("/reset").session(s).with(from("10.5.0.3")).param("_csrf", t).param("token", token)
                        .param("password", "weakweak1").param("confirm", "weakweak1"))
                .andExpect(flash().attribute("error", containsString("stronger")));
        MvcResult done = mockMvc.perform(post("/reset").session(s).with(from("10.5.0.4")).param("_csrf", t).param("token", token)
                        .param("password", "Fresh-Pass-77!").param("confirm", "Fresh-Pass-77!"))
                .andExpect(redirectedUrl("/")).andReturn();
        MockHttpSession active = (MockHttpSession) done.getRequest().getSession(false);
        mockMvc.perform(get("/staff").session(active)).andExpect(status().isOk());

        // the link works only once
        mockMvc.perform(post("/reset").session(s).with(from("10.5.0.5")).param("_csrf", t).param("token", token)
                        .param("password", "Other-Pass-88!").param("confirm", "Other-Pass-88!"))
                .andExpect(flash().attribute("error", containsString("not valid any more")));
        // the new password signs in
        MockHttpSession again = new MockHttpSession();
        String t2 = token(mockMvc.perform(get("/login").session(again)).andReturn());
        mockMvc.perform(post("/login").session(again).with(from("10.5.0.6")).param("username", ida.email()).param("password", "Fresh-Pass-77!").param("_csrf", t2))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void withoutMailSettingsForgotPasswordSaysSo() throws Exception {
        org.mockito.Mockito.when(mail.enabled()).thenReturn(false);
        mockMvc.perform(get("/forgot")).andExpect(content().string(containsString("not set up on this server yet")));
    }
}

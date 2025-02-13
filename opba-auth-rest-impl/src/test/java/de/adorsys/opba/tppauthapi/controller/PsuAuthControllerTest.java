package de.adorsys.opba.tppauthapi.controller;

import com.google.gson.Gson;
import de.adorsys.datasafe.privatestore.api.PasswordClearingOutputStream;
import de.adorsys.datasafe.privatestore.api.PrivateSpaceService;
import de.adorsys.datasafe.types.api.types.ReadKeyPassword;
import de.adorsys.opba.api.security.internal.config.TppTokenProperties;
import de.adorsys.opba.api.security.internal.service.TokenBasedAuthService;
import de.adorsys.opba.db.domain.entity.psu.Psu;
import de.adorsys.opba.db.repository.jpa.psu.PsuRepository;
import de.adorsys.opba.protocol.facade.config.auth.FacadeConsentAuthConfig;
import de.adorsys.opba.protocol.facade.config.encryption.impl.psu.PsuSecureStorage;
import de.adorsys.opba.protocol.facade.services.authorization.PsuLoginService;
import de.adorsys.opba.protocol.facade.services.psu.PsuAuthService;
import de.adorsys.opba.tppauthapi.config.ApplicationTest;
import de.adorsys.opba.tppauthapi.model.generated.PsuAuthBody;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import static de.adorsys.opba.restapi.shared.HttpHeaders.X_REQUEST_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ApplicationTest.class)
@AutoConfigureMockMvc
public class PsuAuthControllerTest {
    private static final String LOGIN = "login";
    private static final String PASSWORD = "password";

    private static final String TPP_AUTH_API_REGISTRATION_URL = "/v1/psu/register";
    private static final String TPP_AUTH_API_LOGIN_URL = "/v1/psu/login";
    private static final Psu PSU = Psu.builder().id(1L).login(LOGIN).build();
    private static final String REDIRECT_TO = "http://example.com";

    @MockBean
    private PsuRepository psuRepository;
    @MockBean
    private PrivateSpaceService privateSpace;
    @MockBean
    private PsuSecureStorage psuSecureStorage;
    @MockBean
    @SuppressWarnings("PMD.UnusedPrivateField") // Injecting into Spring context
    private PsuLoginService psuLoginService;
    @MockBean
    private PsuAuthService psuAuthService;
    @MockBean
    private FacadeConsentAuthConfig consentAuthConfig;
    @MockBean
    private TokenBasedAuthService authService;
    @Autowired
    private TppTokenProperties tppTokenProperties;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @SneakyThrows
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(psuSecureStorage.privateService()).thenReturn(privateSpace);
        FacadeConsentAuthConfig.Redirect redir = new FacadeConsentAuthConfig.Redirect();
        redir.setConsentLogin(new FacadeConsentAuthConfig.Redirect.ConsentLogin());
        redir.getConsentLogin().setPage(new FacadeConsentAuthConfig.Redirect.ConsentLogin.Page());
        redir.getConsentLogin().getPage().setForAis(REDIRECT_TO);
        when(consentAuthConfig.getRedirect()).thenReturn(redir);
        when(authService.generateToken(LOGIN, tppTokenProperties.getTokenValidityDuration())).thenReturn("token");
        when(psuAuthService.tryAuthenticateUser(LOGIN, PASSWORD)).thenReturn(PSU);
    }

    @Test
    void registrationTest() throws Exception {
        when(psuRepository.findByLogin(LOGIN)).thenReturn(Optional.empty());
        when(psuRepository.save(any())).thenReturn(PSU);

        when(privateSpace.write(any())).thenReturn(
                new PasswordClearingOutputStream(
                        new ByteArrayOutputStream(),
                        new ReadKeyPassword("PASSWORD"::toCharArray)
                )
        );

        String xRequestId = UUID.randomUUID().toString();
        var result = mockMvc.perform(post(TPP_AUTH_API_REGISTRATION_URL)
                .header(X_REQUEST_ID, xRequestId)
                .content(new Gson().toJson(getPsuAuthBody()))
                .contentType(APPLICATION_JSON))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", REDIRECT_TO))
                .andDo(print());
    }

    @Test
    void loginTest() throws Exception {
        Optional<Psu> psu = Optional.of(PSU);
        when(psuRepository.findByLogin(LOGIN)).thenReturn(psu);
        when(privateSpace.read(any())).thenReturn(null);

        String xRequestId = UUID.randomUUID().toString();
        var result = mockMvc.perform(post(TPP_AUTH_API_LOGIN_URL)
                .header(X_REQUEST_ID, xRequestId)
                .content(new Gson().toJson(getPsuAuthBody()))
                .contentType(APPLICATION_JSON))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Set-Cookie"))
                .andDo(print())
                .andReturn();
    }

    private PsuAuthBody getPsuAuthBody() {
        PsuAuthBody psuAuthBody = new PsuAuthBody();
        psuAuthBody.setLogin(LOGIN);
        psuAuthBody.setPassword(PASSWORD);
        return psuAuthBody;
    }
}

package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEC-active-account: a deactivated account loses access at its next request, not when its token expires,
 * and cannot log in again.
 */
@DisplayName("Integration tests for the active-account check on every authenticated request")
class ActiveAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtEncoder jwtEncoder;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    PeerEvaluationUser john;

    String studentJohnToken;


    @BeforeEach
    void setUp() throws Exception {
        this.john = this.userRepository.findByUsername("j.smith@abc.edu").orElseThrow();
        this.studentJohnToken = login("j.smith@abc.edu");
    }

    private String login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        JSONObject json = new JSONObject(mvcResult.getResponse().getContentAsString());
        return "Bearer " + json.getJSONObject("data").getString("token");
    }

    private void deactivate(PeerEvaluationUser user) {
        user.setEnabled(false);
        this.userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("An active account's token still works")
    void activeStudentTokenIsAccepted() throws Exception {
        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.studentJohnToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS));
    }

    @Test
    @DisplayName("A deactivated student's unexpired token is refused at her next request")
    void deactivatedStudentTokenIsRefused() throws Exception {
        deactivate(this.john);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.studentJohnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("A deactivated instructor's unexpired token is refused too")
    void deactivatedInstructorTokenIsRefused() throws Exception {
        String billToken = login("b.gates@abc.edu");
        deactivate(this.userRepository.findByUsername("b.gates@abc.edu").orElseThrow());

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, billToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("A deactivated student cannot log in")
    void deactivatedStudentCannotLogIn() throws Exception {
        deactivate(this.john);

        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("A token whose userId names no account is refused with 401, not a server error")
    void tokenForAnAccountThatDoesNotExistIsRefused() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject("nobody@abc.edu")
                .claim("userId", 999999)
                .claim("authorities", "ROLE_student")
                .build();
        String token = "Bearer " + this.jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

}

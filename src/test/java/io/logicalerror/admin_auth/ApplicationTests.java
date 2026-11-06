package io.logicalerror.admin_auth;

import io.logicalerror.admin_auth.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationTests
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedRequestRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void authorizationServerEndpointAreNotAvailableYet() throws Exception {
        mockMvc.perform(get("/oauth2/authorize"))
                .andExpect(status().isForbidden());
    }

    @Test
    void devIdentityCanLogin() throws Exception {
        mockMvc.perform(
                        SecurityMockMvcRequestBuilders.formLogin()
                                .user("test-admin")
                                .password("test-password")
                )
                .andExpect(status().is3xxRedirection());
    }

}

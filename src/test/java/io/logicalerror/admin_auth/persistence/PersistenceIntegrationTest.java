package io.logicalerror.admin_auth.persistence;

import io.logicalerror.admin_auth.support.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class PersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2AuthorizationConsentService authorizationConsentService;
    private final Flyway flyway;

    @Autowired
    public PersistenceIntegrationTest(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            OAuth2AuthorizationConsentService authorizationConsentService,
            Flyway flyway
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.registeredClientRepository = registeredClientRepository;
        this.authorizationService = authorizationService;
        this.authorizationConsentService = authorizationConsentService;
        this.flyway = flyway;
    }


    // A. Testcontainers 공통 환경 : PostgreSQL 18.6과 2-Role 재현

    @Test
    void shouldConnectUsingRuntimeRole() {
        String currentUser = jdbcTemplate.queryForObject(
                "SELECT current_user",
                String.class
        );
    }

    @Test
    void shouldApplyFlywayMigration() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'oauth2_registered_client',
                              'oauth2_authorization',
                              'oauth2_authorization_consent'
                          )
                        """,
                Integer.class
        );

        assertThat(tableCount).isEqualTo(3);
    }


    // B. RegisteredClient 테스트 : 저장·조회·갱신
    private RegisteredClient createTestClient() {
        String id = UUID.randomUUID().toString();

        return RegisteredClient.withId(id)
                .clientId("test-client-" + id)
                .clientName("Test Client")
                .clientSecret("{noop}test-secret" + id)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("test.read")
                .build();
    }


    @Test
    void shouldSaveAndFindRegisteredClientById() {
        RegisteredClient client = createTestClient();

        registeredClientRepository.save(client);
        RegisteredClient found = registeredClientRepository.findById(client.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(client.getId());
        assertThat(found.getClientId()).isEqualTo(client.getClientId());
        assertThat(found.getClientSecret()).isEqualTo(client.getClientSecret());
        assertThat(found.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(found.getScopes()).contains("test.read");
    }

    @Test
    void shouldFindRegisteredClientByClientId() {
        RegisteredClient client = createTestClient();

        registeredClientRepository.save(client);

        RegisteredClient found = registeredClientRepository.findByClientId(client.getClientId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(client.getId());
        assertThat(found.getClientName()).isEqualTo("Test Client");
    }

    @Test
    void shouldUpdateRegisteredClient() {
        RegisteredClient client = createTestClient();

        registeredClientRepository.save(client);

        RegisteredClient updated = RegisteredClient.from(client)
                .clientName("Updated Test Client")
                .scope("test.write")
                .build();
        registeredClientRepository.save(updated);

        RegisteredClient found = registeredClientRepository.findById(client.getId());

        assertThat(found).isNotNull();
        assertThat(found.getClientName()).isEqualTo("Updated Test Client");
        assertThat(found.getScopes()).contains("test.read", "test.write");

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM oauth2_registered_client
                        WHERE id = ?
                        """,
                Integer.class,
                client.getId()
        );

        assertThat(count).isEqualTo(1);
    }

    // C. Authorization 테스트 : 저장·조회·갱신·삭제
    private OAuth2Authorization createTestAuthorization(RegisteredClient registeredClient) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "test-access-token-" + UUID.randomUUID(),
                issuedAt,
                issuedAt.plus(1, ChronoUnit.HOURS),
                Set.of("test.read")
        );

        return OAuth2Authorization.withRegisteredClient(registeredClient)
                .principalName(registeredClient.getClientId())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizedScopes(Set.of("test.read"))
                .accessToken(accessToken)
                .build();
    }

    @Test
    void shouldSaveAndFindAuthorizationById() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        OAuth2Authorization authorization = createTestAuthorization(client);
        authorizationService.save(authorization);

        OAuth2Authorization found = authorizationService.findById(authorization.getId());
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(authorization.getId());
        assertThat(found.getRegisteredClientId()).isEqualTo(client.getId());
        assertThat(found.getPrincipalName()).isEqualTo(client.getClientId());
        assertThat(found.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(found.getAuthorizedScopes()).contains("test.read");
        assertThat(found.getAccessToken()).isNotNull();
        assertThat(found.getAccessToken().getToken().getTokenValue())
                .isEqualTo(authorization.getAccessToken().getToken().getTokenValue());
    }

    @Test
    void shouldFindAuthorizationByAccessToken() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        OAuth2Authorization authorization = createTestAuthorization(client);
        authorizationService.save(authorization);

        String accessTokenValue = authorization.getAccessToken().getToken().getTokenValue();

        OAuth2Authorization found = authorizationService.findByToken(
                accessTokenValue,
                OAuth2TokenType.ACCESS_TOKEN
        );

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(authorization.getId());
        assertThat(found.getAccessToken().getToken().getTokenValue()).isEqualTo(accessTokenValue);
    }

    @Test
    void shouldUpdateAuthorization() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        OAuth2Authorization authorization = createTestAuthorization(client);
        authorizationService.save(authorization);

        OAuth2AccessToken accessToken = authorization.getAccessToken().getToken();

        OAuth2Authorization updated = OAuth2Authorization.from(authorization)
                .token(accessToken, metadata -> metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true))
                .build();

        authorizationService.save(updated);
        OAuth2Authorization found = authorizationService.findById(authorization.getId());

        assertThat(found).isNotNull();
        assertThat(found.getAccessToken()).isNotNull();
        assertThat(found.getAccessToken().isInvalidated()).isTrue();
        assertThat(found.getAccessToken().isActive()).isFalse();

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM oauth2_authorization
                        WHERE id = ?
                        """,
                Integer.class,
                authorization.getId()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldRemoveAuthorization() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        OAuth2Authorization authorization = createTestAuthorization(client);
        authorizationService.save(authorization);

        String accessTokenValue = authorization.getAccessToken().getToken().getTokenValue();

        authorizationService.remove(authorization);

        OAuth2Authorization foundById = authorizationService.findById(authorization.getId());
        OAuth2Authorization foundByToken = authorizationService.findByToken(
                accessTokenValue,
                OAuth2TokenType.ACCESS_TOKEN
        );

        assertThat(foundById).isNull();
        assertThat(foundByToken).isNull();
    }

    // D. Consent 테스트 : 저장·조회·삭제
    private OAuth2AuthorizationConsent createTestConsent(RegisteredClient registeredClient, String principalName) {
        return OAuth2AuthorizationConsent.withId(registeredClient.getId(), principalName)
                .scope("test.read")
                .build();
    }

    @Test
    void shouldSaveAndFindAuthorizationConsent() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        String principalName = "test-user-" + UUID.randomUUID();

        OAuth2AuthorizationConsent consent = createTestConsent(client, principalName);
        authorizationConsentService.save(consent);

        OAuth2AuthorizationConsent found = authorizationConsentService.findById(client.getId(), principalName);
        assertThat(found).isNotNull();
        assertThat(found.getRegisteredClientId()).isEqualTo(client.getId());
        assertThat(found.getPrincipalName()).isEqualTo(principalName);
        assertThat(found.getAuthorities()).contains(new SimpleGrantedAuthority("SCOPE_test.read"));
    }

    @Test
    void shouldUpdateAuthorizationConsent() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        String principalName = "test-user-" + UUID.randomUUID();

        OAuth2AuthorizationConsent consent = createTestConsent(client, principalName);
        authorizationConsentService.save(consent);

        OAuth2AuthorizationConsent updated = OAuth2AuthorizationConsent.from(consent)
                .scope("test.write")
                .build();
        authorizationConsentService.save(updated);

        OAuth2AuthorizationConsent found = authorizationConsentService.findById(client.getId(), principalName);
        assertThat(found).isNotNull();
        assertThat(found.getAuthorities()).containsExactlyInAnyOrder(
                new SimpleGrantedAuthority("SCOPE_test.read"),
                new SimpleGrantedAuthority("SCOPE_test.write")
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM oauth2_authorization_consent
                        WHERE registered_client_id = ?
                          AND principal_name = ?
                        """,
                Integer.class,
                client.getId(),
                principalName
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldRemoveAuthorizationConsent() {
        RegisteredClient client = createTestClient();
        registeredClientRepository.save(client);

        String principleName = "test-user-" + UUID.randomUUID();

        OAuth2AuthorizationConsent consent = createTestConsent(client, principleName);
        authorizationConsentService.save(consent);

        authorizationConsentService.remove(consent);
        OAuth2AuthorizationConsent found = authorizationConsentService.findById(client.getId(), principleName);
        assertThat(found).isNull();
    }


    // E. DB Contract 및 기존 테스트 정리 : 권한·Fail-Fast·CI
    @Test
    void shouldDenyDdlForRuntimeRole() {
        Boolean canCreate = jdbcTemplate.queryForObject(
                """
                        SELECT has_schema_privilege(
                            current_user,
                            'public',
                            'CREATE'
                        )
                        """,
                Boolean.class
        );
        assertThat(canCreate).isFalse();


        assertThatThrownBy(() ->
                jdbcTemplate.execute(
                        """
                                CREATE TABLE public.runtime_ddl_probe (
                                    id INTEGER
                                )
                                """
                )
        ).isInstanceOf(DataAccessException.class);
    }

    @Test
    void shouldSeparateMigrationAndRuntimeRoles() throws Exception {
        String runtimeUser = jdbcTemplate.queryForObject(
                "SELECT current_user",
                String.class
        );

        String migrationUser;
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_user")
        ) {
            assertThat(resultSet.next()).isTrue();
            migrationUser = resultSet.getString(1);
        }

        assertThat(runtimeUser).isEqualTo("admin_auth_runtime");
        assertThat(migrationUser).isEqualTo("admin_auth_migration");
        assertThat(runtimeUser).isNotEqualTo(migrationUser);
    }

    @Test
    void shouldUseJdbcPersistenceImplementations() {
        assertThat(registeredClientRepository).isInstanceOf(JdbcRegisteredClientRepository.class);
        assertThat(authorizationService).isInstanceOf(JdbcOAuth2AuthorizationService.class);
        assertThat(authorizationConsentService).isInstanceOf(JdbcOAuth2AuthorizationConsentService.class);
    }
}


package tech.kayys.andalus.agent.api;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ForbiddenException;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.core.core.AgentClient;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.agent.spi.AgentResponse;
import tech.kayys.andalus.execution.AgentCallerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCallerContextRestTest {

    @Test
    void trustedCallBuildsCallerFromVerifiedIdentityAndStripsBodyClaims() {
        AgentResource resource = new AgentResource();
        resource.securityIdentity = identity(false, "tenant-a", Set.of("agent:run"),
                Set.of("tool:read", "tool:invoke"));

        AgentResource.TrustedAgentRequest trusted = resource.trustedCall(
                new AgentResource.AgentRunRequest("hello", null, null, null, 0, null,
                        "forged-tenant", "forged-user", null, null,
                        Map.of("tenantId", "forged-tenant", "userId", "forged-user",
                                "roles", Set.of("admin"), "permissions", Set.of("*"), "traceId", "trace-1")));

        assertThat(trusted.request().tenantId()).isEqualTo("tenant-a");
        assertThat(trusted.request().userId()).isEqualTo("user-a");
        assertThat(trusted.request().context()).containsOnlyKeys("traceId");
        assertThat(trusted.caller().tenantId()).isEqualTo("tenant-a");
        assertThat(trusted.caller().userId()).isEqualTo("user-a");
        assertThat(trusted.caller().principalId()).isEqualTo("user-a");
        assertThat(trusted.caller().roles()).containsExactly("agent:run");
        assertThat(trusted.caller().permissions()).containsExactlyInAnyOrder("tool:read", "tool:invoke");
        assertThat(trusted.caller().principalType()).isEqualTo(AgentCallerContext.PrincipalType.USER);
    }

    @Test
    void runPassesCallerContextToTheContextAwareClientMethod() {
        AgentResource resource = new AgentResource();
        resource.securityIdentity = identity(false, "tenant-a", Set.of("agent:run"), Set.of("tool:read"));
        AtomicReference<AgentCallerContext> captured = new AtomicReference<>();
        resource.agentClient = new AgentClient() {
            @Override
            public Uni<AgentResponse> execute(AgentRequest request) {
                throw new AssertionError("REST must not call the identity-dropping legacy overload");
            }

            @Override
            public Uni<AgentResponse> execute(AgentRequest request, AgentCallerContext caller) {
                captured.set(caller);
                return Uni.createFrom().item(AgentResponse.builder()
                        .requestId(request.requestId())
                        .answer("accepted")
                        .successful(true)
                        .build());
            }
        };

        var response = resource.run(request()).await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().tenantId()).isEqualTo("tenant-a");
        assertThat(captured.get().userId()).isEqualTo("user-a");
        assertThat(captured.get().roles()).containsExactly("agent:run");
        assertThat(captured.get().permissions()).containsExactly("tool:read");
    }

    @Test
    void authenticatedIdentityWithoutTenantIsRejected() {
        AgentResource resource = new AgentResource();
        resource.securityIdentity = identity(false, null, Set.of("agent:run"), Set.of());

        assertThatThrownBy(() -> resource.trustedCall(request()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void wildcardPermissionClaimIsRejected() {
        AgentResource resource = new AgentResource();
        resource.securityIdentity = identity(false, "tenant-a", Set.of("agent:run"), Set.of("*"));

        assertThatThrownBy(() -> resource.trustedCall(request()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anonymousRequestProducesUnscopedAnonymousCaller() {
        AgentResource resource = new AgentResource();
        resource.securityIdentity = identity(true, null, Set.of(), null);

        AgentResource.TrustedAgentRequest trusted = resource.trustedCall(request());

        assertThat(trusted.caller().principalType()).isEqualTo(AgentCallerContext.PrincipalType.ANONYMOUS);
        assertThat(trusted.caller().tenantId()).isNull();
        assertThat(trusted.caller().roles()).isEmpty();
        assertThat(trusted.caller().permissions()).isEmpty();
        assertThat(trusted.request().tenantId()).isNull();
        assertThat(trusted.request().userId()).isNull();
    }

    @Test
    void legacyClientFallbackIsAllowedOnlyForUnscopedAnonymousCaller() {
        int[] calls = {0};
        AgentClient legacy = request -> {
            calls[0]++;
            return Uni.createFrom().failure(new IllegalStateException("legacy invocation observed"));
        };

        assertThatThrownBy(() -> legacy.execute(AgentRequest.builder().prompt("hello").build(),
                AgentCallerContext.user("tenant-a", "user-a", Set.of("agent:run"), Set.of()))
                .await().indefinitely())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(calls[0]).isZero();

        assertThatThrownBy(() -> legacy.execute(AgentRequest.builder().prompt("hello").build(),
                AgentCallerContext.anonymous(null)).await().indefinitely())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("legacy invocation observed");
        assertThat(calls[0]).isEqualTo(1);
    }

    private AgentResource.AgentRunRequest request() {
        return new AgentResource.AgentRunRequest("hello", null, null, null, 0, null,
                "body-tenant", "body-user", null, null, null);
    }

    private SecurityIdentity identity(boolean anonymous, String tenantId,
            Set<String> roles, Set<String> permissions) {
        return (SecurityIdentity) Proxy.newProxyInstance(
                SecurityIdentity.class.getClassLoader(),
                new Class<?>[] { SecurityIdentity.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "isAnonymous" -> anonymous;
                    case "getPrincipal" -> anonymous ? null : (Principal) () -> "user-a";
                    case "getAttribute" -> switch ((String) args[0]) {
                        case "tenantId" -> tenantId;
                        case "permissions" -> permissions;
                        default -> null;
                    };
                    case "getRoles" -> roles;
                    case "hasRole" -> roles != null && roles.contains(args[0]);
                    default -> null;
                });
    }
}

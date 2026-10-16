package tech.kayys.andalus.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@DefaultBean
public class HttpMcpToolDiscoveryClient implements McpToolDiscoveryClient {

    static final String CONTEXT_CLIENT_NAME = "mcpClientName";
    static final String CONTEXT_CLIENT_VERSION = "mcpClientVersion";
    static final String CONTEXT_MAX_DISCOVERY_PAGES = "mcpMaxDiscoveryPages";

    private static final String DEFAULT_CLIENT_NAME = "andalus-aqli";
    private static final String DEFAULT_CLIENT_VERSION = "1.0.0";
    private static final int DEFAULT_MAX_DISCOVERY_PAGES = 25;

    private final McpHttpJsonRpcClient jsonRpc;

    public HttpMcpToolDiscoveryClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    HttpMcpToolDiscoveryClient(HttpClient httpClient, ObjectMapper mapper) {
        this(new McpHttpJsonRpcClient(httpClient, mapper));
    }

    HttpMcpToolDiscoveryClient(McpHttpJsonRpcClient jsonRpc) {
        this.jsonRpc = jsonRpc;
    }

    @Override
    public Uni<McpToolDiscoveryResult> discoverTools(McpToolDiscoveryRequest request) {
        Instant startedAt = Instant.now();
        Map<String, Object> context = request.effectiveContext();
        return initialize(context)
                .flatMap(initialize -> {
                    McpToolDiscoveryResult failure = failureIfAny(request, initialize, startedAt);
                    if (failure != null) {
                        return Uni.createFrom().item(failure);
                    }
                    String protocolVersion;
                    try {
                        protocolVersion = negotiatedProtocolVersion(context, initialize);
                    } catch (McpToolDiscoveryProtocolException error) {
                        return Uni.createFrom().item(protocolParseFailure(
                                request, initialize, error, startedAt));
                    }
                    Map<String, Object> initializedContext = withProtocolVersion(context, protocolVersion);
                    return jsonRpc.notification(initializedContext, McpMethods.INITIALIZED_NOTIFICATION, Map.of())
                            .onFailure().recoverWithNull()
                            .flatMap(ignored -> listTools(
                                    request,
                                    initializedContext,
                                    protocolVersion,
                                    null,
                                    0,
                                    new ArrayList<>(),
                                    startedAt));
                })
                .onFailure().recoverWithItem(error -> McpToolDiscoveryResult.failure(
                        request.serverName(),
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                        elapsedMs(startedAt),
                        McpFailureType.metadata(McpFailureType.TRANSPORT)));
    }

    private Uni<McpHttpJsonRpcResponse> initialize(Map<String, Object> context) {
        return jsonRpc.request(
                context,
                McpMethods.INITIALIZE,
                McpToolDiscoveryProtocol.initializeParams(
                        jsonRpc.protocolVersion(context),
                        stringValue(context.get(CONTEXT_CLIENT_NAME), DEFAULT_CLIENT_NAME),
                        stringValue(context.get(CONTEXT_CLIENT_VERSION), DEFAULT_CLIENT_VERSION)));
    }

    private Uni<McpToolDiscoveryResult> listTools(
            McpToolDiscoveryRequest request,
            Map<String, Object> context,
            String protocolVersion,
            String cursor,
            int page,
            List<McpDiscoveredTool> tools,
            Instant startedAt) {
        if (page >= maxDiscoveryPages(context)) {
            return Uni.createFrom().item(McpToolDiscoveryResult.failure(
                    request.serverName(),
                    "MCP tools/list exceeded max discovery pages",
                    elapsedMs(startedAt),
                    McpToolDiscoveryMetadata.pages(page)));
        }

        return jsonRpc.request(context, McpMethods.TOOLS_LIST, McpToolDiscoveryProtocol.toolsListParams(cursor))
                .flatMap(response -> {
                    McpToolDiscoveryResult failure = failureIfAny(request, response, startedAt);
                    if (failure != null) {
                        return Uni.createFrom().item(failure);
                    }

                    McpToolDiscoveryProtocol.ToolsListPayload result;
                    try {
                        result = McpToolDiscoveryProtocol.toolsList(response.result());
                    } catch (McpToolDiscoveryProtocolException error) {
                        return Uni.createFrom().item(protocolParseFailure(
                                request, response, error, startedAt));
                    }
                    result.tools().stream()
                            .map(item -> McpDiscoveredTool.from(request.serverName(), item))
                            .forEach(tools::add);

                    String nextCursor = result.nextCursor();
                    if (nextCursor != null && !nextCursor.isBlank()) {
                        return listTools(request, context, protocolVersion, nextCursor, page + 1, tools, startedAt);
                    }

                    return Uni.createFrom().item(McpToolDiscoveryResult.success(
                            request.serverName(),
                            protocolVersion,
                            tools,
                            elapsedMs(startedAt),
                            McpToolDiscoveryMetadata.success(response.endpoint(), page + 1, tools.size())));
                });
    }

    private McpToolDiscoveryResult failureIfAny(
            McpToolDiscoveryRequest request,
            McpHttpJsonRpcResponse response,
            Instant startedAt) {
        if (!response.httpOk()) {
            return McpToolDiscoveryResult.failure(
                    request.serverName(),
                    "MCP HTTP " + response.httpStatus(),
                    elapsedMs(startedAt),
                    response.metadata(McpFailureType.HTTP));
        }
        McpJsonRpcError error = response.jsonRpcError();
        if (error != null) {
            return McpToolDiscoveryResult.failure(
                    request.serverName(),
                    error.message(),
                    elapsedMs(startedAt),
                    response.metadata(response.failureTypeOr(McpFailureType.JSON_RPC)));
        }
        return null;
    }

    private String negotiatedProtocolVersion(Map<String, Object> context, McpHttpJsonRpcResponse response) {
        return McpToolDiscoveryProtocol.initializeProtocolVersion(
                response.result(),
                jsonRpc.protocolVersion(context));
    }

    private McpToolDiscoveryResult protocolParseFailure(
            McpToolDiscoveryRequest request,
            McpHttpJsonRpcResponse response,
            McpToolDiscoveryProtocolException error,
            Instant startedAt) {
        return McpToolDiscoveryResult.failure(
                request.serverName(),
                error.getMessage(),
                elapsedMs(startedAt),
                response.metadata(McpFailureType.PARSE));
    }

    private Map<String, Object> withProtocolVersion(Map<String, Object> context, String protocolVersion) {
        Map<String, Object> values = new LinkedHashMap<>(context);
        values.put(McpHttpJsonRpcClient.CONTEXT_PROTOCOL_VERSION, protocolVersion);
        return McpMaps.copy(values);
    }

    private int maxDiscoveryPages(Map<String, Object> context) {
        Object value = context.get(CONTEXT_MAX_DISCOVERY_PAGES);
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                int parsed = Integer.parseInt(string);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                return DEFAULT_MAX_DISCOVERY_PAGES;
            }
        }
        return DEFAULT_MAX_DISCOVERY_PAGES;
    }

    private String stringValue(Object value, String fallback) {
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        return fallback;
    }

    private long elapsedMs(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }
}

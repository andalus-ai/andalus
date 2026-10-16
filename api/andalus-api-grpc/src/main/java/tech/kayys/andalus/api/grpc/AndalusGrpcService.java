package tech.kayys.andalus.api.grpc;

import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import tech.kayys.andalus.client.Andalus;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusPlatformApi;
import tech.kayys.andalus.command.AndalusCommandApi;
import tech.kayys.andalus.workbench.WorkbenchCommandQuery;

@GrpcService
public class AndalusGrpcService implements AndalusService {

    private final AndalusAqliSdk sdk = Andalus.local();

    @Override
    public Uni<StatusReply> getStatus(StatusRequest request) {
        if (sdk == null) {
            return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        }
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.statusJson(platformApi.status());
        return Uni.createFrom().item(StatusReply.newBuilder().setStatusJson(json).build());
    }

    @Override
    public Uni<ReadinessReply> getReadiness(ReadinessRequest request) {
        if (sdk == null) {
            return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        }
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.readinessJson(platformApi.readiness());
        return Uni.createFrom().item(ReadinessReply.newBuilder().setReadinessJson(json).build());
    }

    @Override
    public Uni<ReadinessReply> getReadinessProfile(ReadinessProfileRequest request) {
        if (sdk == null) {
            return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        }
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.readinessProfileDetailJson(platformApi.readinessProfile(request.getProfileId()));
        return Uni.createFrom().item(ReadinessReply.newBuilder().setReadinessJson(json).build());
    }

    @Override
    public Uni<CatalogReply> getProductCatalog(CatalogRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.productCatalogJson();
        return Uni.createFrom().item(CatalogReply.newBuilder().setCatalogJson(json).build());
    }

    @Override
    public Uni<ProfilesReply> getProfilesForSurface(SurfaceProfilesRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String surfaceId = request.getSurfaceId();
        String json = platformApi.profilesJson(surfaceId, platformApi.productProfilesForSurface(surfaceId));
        return Uni.createFrom().item(ProfilesReply.newBuilder().setProfilesJson(json).build());
    }

    @Override
    public Uni<ProfileDetailReply> getProfileDetail(ProfileDetailRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.profileDetailJson(platformApi.productProfile(request.getProfileId()));
        return Uni.createFrom().item(ProfileDetailReply.newBuilder().setProfileJson(json).build());
    }

    @Override
    public Uni<BoundaryCatalogReply> getSdkBoundaryCatalog(BoundaryCatalogRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.sdkBoundaryCatalogJson();
        return Uni.createFrom().item(BoundaryCatalogReply.newBuilder().setCatalogJson(json).build());
    }

    @Override
    public Uni<BoundaryDetailReply> getSdkBoundaryDetail(BoundaryDetailRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusPlatformApi platformApi = AndalusClient.of(sdk).platform();
        String json = platformApi.sdkBoundaryJson(request.getBoundaryId());
        return Uni.createFrom().item(BoundaryDetailReply.newBuilder().setBoundaryJson(json).build());
    }

    @Override
    public Uni<CommandDiscoveryReply> discoverCommands(CommandQueryRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusCommandApi commandApi = AndalusClient.of(sdk).commands();
        WorkbenchCommandQuery query = WorkbenchCommandQuery.of(request.getSurfaceId(), request.getCategory(), request.getCommandId());
        return Uni.createFrom().item(CommandDiscoveryReply.newBuilder().setDiscoveryJson(commandApi.discoveryJson(query)).build());
    }

    @Override
    public Uni<CommandIndexReply> indexCommands(CommandQueryRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusCommandApi commandApi = AndalusClient.of(sdk).commands();
        WorkbenchCommandQuery query = WorkbenchCommandQuery.of(request.getSurfaceId(), request.getCategory(), request.getCommandId());
        return Uni.createFrom().item(CommandIndexReply.newBuilder().setIndexJson(commandApi.indexJson(query)).build());
    }

    @Override
    public Uni<WorkbenchReply> workbenchCommands(CommandQueryRequest request) {
        if (sdk == null) return Uni.createFrom().failure(new RuntimeException("SDK not initialized"));
        AndalusCommandApi commandApi = AndalusClient.of(sdk).commands();
        WorkbenchCommandQuery query = WorkbenchCommandQuery.of(request.getSurfaceId(), request.getCategory(), request.getCommandId());
        return Uni.createFrom().item(WorkbenchReply.newBuilder().setWorkbenchJson(commandApi.workbenchJson(query)).build());
    }
}

# AND-5.3.5 — Authenticated Capability Catalog API

## Endpoint

`GET /api/workflow/node-capabilities`

The endpoint is authenticated and read-only. It supports these presentation filters:

- `q`: text search
- `category`: node category
- `provider`: provider ID
- `offset`: zero-based page offset (default 0)
- `limit`: page size (default 50, maximum 200)

The response contains the catalog version and generation timestamp, page metadata, total number of visible descriptors, and the current page of node descriptors.

## Trusted authorization boundary

The endpoint never accepts tenant IDs, subject IDs, permission grants, available capabilities, or framework versions from the client.

A deployment supplies a CDI `CatalogAccessContextResolver` implementation. It receives Quarkus' authenticated `SecurityIdentity` and must resolve tenant membership, subject identity, permissions, runtime capabilities, and framework version from trusted server-side identity and policy sources.

If no resolver is configured, the endpoint returns service unavailable rather than falling back to a public or unscoped catalog. If the resolver cannot produce an authorized context, access is forbidden. The existing catalog service then applies permission, capability, and framework compatibility visibility filters.

This endpoint only controls metadata visibility. Workflow validation, publication, and execution must independently enforce authorization.

## Ownership

The endpoint and its resolver SPI live in Andalus Runtime's workflow runtime. Portable catalog descriptors remain owned by Andalus Framework. Tenant/policy integration is supplied by the deployment adapter; the workflow module does not duplicate tenant contracts or trust designer-supplied claims.

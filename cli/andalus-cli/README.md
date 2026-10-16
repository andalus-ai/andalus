# Andalus CLI

Andalus CLI is the product-facing command line and terminal UI for the Andalus
agentic platform.

## Boundary

- Aqli remains the inference, serving, and training engine.
- Gamelan remains the workflow execution engine.
- Andalus owns the agentic platform surface above both engines: agents,
  skills, tools, MCP, RAG, memory, workflows, and harness operations.

Tamboui is treated as a renderer reference, not the platform contract. The core
CLI model is closer to agent workbench tools such as Gemini CLI or Claude Code:
workspace-aware, command-driven, and able to operate agents, tools, workflows,
retrieval, memory, and harness checks from one terminal surface.

The CLI consumes `andalus-aqli-sdk` for the agent contract. Requests, run
results, product surfaces, platform status, workbench state, and the bridge into
`AgentRequest` live in the SDK so CLI, TUI, API, and future product shells do
not grow parallel agent models.

Future adapters can wire the same command surface to local Quarkus services,
remote platform APIs, packaged coding-agent runtimes, assistant-agent products,
or low-code agentic workflow products without coupling this module to Aqli's
own CLI.

## Commands

```bash
andalus status
andalus products
andalus products --json
andalus sdk-boundaries
andalus sdk-boundaries --json
andalus sdk-boundaries run --json
andalus profiles
andalus profiles --surface workflow-platform --json
andalus profiles inspect openclaw-agent
andalus workspace --path .
andalus workspace --path . --json
andalus harness --path .
andalus harness --path . --json
andalus spec validate --path andalus-run.properties
andalus spec validate --path andalus-run.properties --json
andalus spec template --surface coding-agent
andalus spec template --profile openclaw-agent
andalus spec template --surface coding-agent --output andalus-run.properties
andalus run "Plan the next RAG evaluation"
andalus run "Fix this repository" --profile openclaw-agent
andalus run --prompt-file task.md
andalus run --stdin
andalus run "Plan the next RAG evaluation" --json
andalus run --spec andalus-run.properties --dry-run --json
andalus run "Plan the next RAG evaluation" --print-spec
andalus run "Plan the next RAG evaluation" --print-spec --output andalus-run.properties
andalus run --prompt-file task.md --system-file system.md
andalus run "Plan the next RAG evaluation" --dry-run
andalus run "Plan the next RAG evaluation" --dry-run --json
andalus run "Plan the next RAG evaluation" --preflight
andalus run "Plan the next RAG evaluation" --preflight --json
andalus contracts --json
andalus contracts --index --json
andalus contracts --envelope run-preview --schema-json
andalus contracts --json-schema-id urn:andalus:contract:andalus.skill.catalog:v1:skill-discovery --schema-json
andalus contracts --domain planning --schema-bundle-json
andalus contracts --check --json
andalus contracts --coverage --json
andalus contracts --command-id run-dry-json --json
andalus contracts --json-schema-id urn:andalus:contract:andalus.run.planning:v1:run-preview --json
andalus contracts --domain lifecycle --json
andalus contracts --schema andalus.run.lifecycle --json
andalus contracts --envelope run-preview --json
andalus commands --contract-json-schema-id urn:andalus:contract:andalus.run.planning:v1:run-preview --json
andalus commands --contract-json-schema-id urn:andalus:contract:andalus.platform.catalog:v1:profile-list --index --json
andalus commands --contract-json-schema-id urn:andalus:contract:andalus.skill.catalog:v1:skill-discovery --index --json
andalus commands --contract-json-schema-id urn:andalus:contract:andalus.command.discovery:v1:commands-discovery --index --json
andalus commands --contract-json-schema-id urn:andalus:contract:andalus.workbench.discovery:v1:workbench-discovery --index --json
andalus workbench --contract-json-schema-id urn:andalus:contract:andalus.run.planning:v1:run-preview --json
andalus run "Answer a support question" --surface assistant-agent
andalus run "Answer a support question" --surface assistant-agent --session chat-1 --user user-1
andalus run "Answer with docs" --context rag.collection=docs --context mcp.server=filesystem
andalus run "Plan repo changes" --workspace .
andalus run "Make a guarded change" --workspace . --harness
andalus run "Make a guarded change" --workspace . --harness --json
andalus run status <run-id> --json
andalus run inspect <run-id> --json
andalus run events <run-id> --json
andalus run events <run-id> --state completed --type run.completed --limit 20 --json
andalus run events <run-id> --after-sequence 10 --limit 20 --json
andalus run events <run-id> --follow --json
andalus run events <run-id> --follow --follow-result --json
andalus run events <run-id> --follow --follow-result-only --json
andalus run events <run-id> --follow --follow-result-only --stats --json
andalus run events <run-id> --stats --json
andalus run list --state completed --limit 10 --json
andalus run list --offset 10 --limit 10 --json
andalus run stats --state completed --json
andalus run list --tenant tenant-a --session chat-1 --surface assistant-agent --json
andalus run list --profile low-code-agent --json
andalus run stats --profile low-code-agent --json
andalus run wait <run-id> --timeout-seconds 30 --json
andalus run cancel <run-id> --reason "user stop" --json
andalus --run-store .andalus/runs.properties run list --state completed --json
andalus --run-store .andalus/runs.properties run forget <run-id> --json
andalus code
andalus code --once "inspect this code"
andalus code --workspace . --harness
andalus commands
andalus commands --surface assistant-agent --json
andalus commands --index --json
andalus workbench
andalus workbench --json
andalus workbench --surface assistant-agent --json
andalus tui
```

`andalus-aqli` remains a compatibility alias for the command name and Maven
artifact/module names remain stable while the product-facing platform name is
shortened to Andalus.

---

Andalus CLI - project & session commands

New commands added:

- andalus project add [<id>] --dir <directory>
- andalus project remove <id>
- andalus project rename <id> --name <new-name>
- andalus project export <id> --file <path>
- andalus project import --file <path>
- andalus project switch <id>

- andalus code --project <project-id>
  - Slash commands inside REPL:
    - /projects - list available projects
    - /project <id> - switch current project
    - /sessions - list sessions for current project
    - /sessions resume <id> - resume a session
    - /sessions delete <id> - delete a stored session transcript

Sessions are stored under ~/.andalus/sessions/{project}/session-<id>.json
Current project pointer stored at ~/.andalus/current_project.txt


Global SDK options are accepted before the subcommand:

```bash
andalus --sdk-mode LOCAL --default-tenant tenant-a --default-model qwen run "Plan"
andalus --sdk-mode REMOTE --endpoint https://andalus.example.com status
```

The remote mode is intentionally provider-backed. Until a remote SDK provider is
on the classpath, the CLI exits clearly instead of pretending to call an API.
Add `andalus-aqli-sdk-remote` to the runtime classpath to enable the HTTP
remote provider, or use the packaged `andalus-aqli-cli-remote` distribution.

`tui` uses TamboUI for the terminal dashboard. The command/service boundary is
Andalus-owned so TamboUI can evolve as an adapter instead of becoming the core
platform contract.

`code` starts a Gemini CLI / Claude Code style coding-agent session on top of
Aqli inference. Each session receives a Andalus-owned system prompt with the
current workspace, profile, memory mode, harness mode, and coding-agent
operating rules. The prompt keeps the CLI focused on repository inspection,
small changes, SDK/API separation of concern, verification, and honest handling
of missing context.

`workbench` renders the same SDK-owned model without opening a terminal UI.
That model is the stable seam for future Claude Code/Gemini CLI style products,
plain CI logs, richer TamboUI screens, and external API-backed launchers.
Use `workbench --json` when a product shell needs the status, catalog,
command palette, structured command metadata, and next-action model as one
machine-readable payload. Add `--surface <id>` when a product shell wants only
the general commands plus commands relevant to one surface such as
`coding-agent`, `assistant-agent`, or `workflow-platform`.

`commands` renders only the SDK-owned command catalog. Use `commands --json`
for machine-readable action discovery, `commands --index --json` for compact
category/id metadata, and `commands --surface <id>` when a product shell needs
only the general commands plus one surface's recommended actions.

`run status <run-id>`, `run inspect <run-id>`, `run events <run-id>`, and `run
list` return the SDK-owned lifecycle shapes for snapshots, combined inspection,
timelines, and history. `run inspect` accepts the same event filters as
`run events` and returns status plus timeline in one envelope with a top-level
`outcome` derived from the combined lifecycle data. Status JSON
exposes an SDK-owned `outcome` from `AgentRunOutcomes`, such as `terminal`,
`pending`, or `unknown`. `run events`
accepts `--state <state>`, `--type <event-type>`, `--after-sequence <n>`, and
`--limit <n>` to support bounded incremental polling. Add `--follow` for a
cursor loop that stops at a terminal event or `--max-polls`, with
`--poll-millis` controlling the interval. Add `--follow-result` to append a
final `run-events-follow` envelope after streamed event-window envelopes. Use
`--follow-result-only` for one final envelope with the last event window nested
under `lastEvents`; combine it with `--stats` to omit event rows from that
nested envelope. Final follow envelopes expose an SDK-owned `outcome` from
`AgentRunOutcomes`, such as `terminal` or `max-polls`, plus `terminalState`,
`terminalEventType`, and `terminalSequence` at the top level so product shells
can route terminal outcomes without parsing nested event rows. SDK callers can use
`followRunEvents(runId, AgentRunEventsFollowOptions, Consumer<AgentRunEvents>)`
to share the same cursor loop outside the CLI. `run list` accepts `--state <state>`,
`--limit <n>`, `--offset <n>`, `--tenant <id>`, `--session <id>`, and
`--surface <id>` or `--profile <id>` so shells can keep product history views bounded and paged.
History envelopes expose an SDK-owned `outcome` such as `terminal`, `pending`,
`unknown`, or `empty` for page-level routing. `run stats` accepts the same
history filters and returns page plus summary envelopes without run rows.
History JSON includes `returnedRuns`, `windowStart`, `windowEnd`,
`previousOffset`, `nextOffset`, `hasPrevious`, `hasMore`, `truncated`,
`stateCounts`, `surfaceCounts`, `profileCounts`, and `strategyCounts` over the returned run
window for dashboards. It also groups the pagination fields under `page` so
consumers can bind one stable page envelope, and groups count maps plus sorted
facet summary arrays under `summary` for dashboards. Local SDK
instances keep an
in-memory run store, while separate CLI invocations still return `UNKNOWN` or
an empty list for runs they did not submit. The store boundary is explicit so
persistent submit/status/list/event flows can be added without changing CLI
shape. Run lifecycle JSON includes a top-level `contract` object with
`schema=andalus.run.lifecycle`, `version=1`, and the envelope name, so TUI and
product shells can route payloads before binding fields. The lifecycle Draft
2020-12 schemas now describe result, status, event timeline, follow,
inspection, history, wait, cancel, and forget payloads, including nested
handles, cursors, pages, summaries, status rows, event rows, and metadata maps.
Event JSON includes an
SDK-owned `outcome` such as `terminal`, `pending`, or `empty`, plus
`firstSequence`, `lastSequence`, `nextAfterSequence`, `truncated`,
`stateCounts`, and `typeCounts`, and a nested `cursor` object with
`remainingEvents` and `advanced`, so pollers and dashboards can bind cursor
state without parsing event arrays themselves. Event counts and sorted event
facet summary arrays are also grouped under `summary`. `run events --stats`
returns the same cursor and summary
envelopes without event rows.
Use the top-level `--run-store <path>` option, or `ANDALUS_RUN_STORE`, when a
local CLI session should persist run snapshots across process boundaries.
`run forget <run-id>` removes one local snapshot from that store without
claiming to cancel an in-flight remote run.
Remote SDK mode uses the same CLI verbs and delegates status/history reads and
`run cancel <run-id>` requests to remote Andalus API endpoints. `run wait
<run-id>` is SDK-owned polling over `run status`, so local and remote products
share one terminal-state contract. Wait JSON exposes an SDK-owned `outcome`
from `AgentRunOutcomes`, such as `terminal`, `timeout`, or `unknown`, matching
the routing style of event follow results. Cancel JSON uses the same outcome
vocabulary for `cancelled`, `not-cancellable`, and `not-found`; forget JSON
uses `forgotten` and `not-found`.

`workspace` inspects repo context for coding-agent products: git root/branch,
build descriptors, package managers, modules, and top-level paths. `run` can
attach that same compact context with `--workspace <path>` so agent products
start from repo shape rather than prompt text alone.

`harness` plans verification checks from the same workspace contract. It emits
command arrays as readable command lines, marks checks as required or optional,
and does not execute them. Use `--required-only` when a product wants only
high-confidence checks. `run --harness` attaches that same verification plan
to the core agent request under the SDK-owned harness context.

`run --surface <id>` selects the product surface consuming the shared Andalus
agent engine. The default is `coding-agent`; use ids such as `assistant-agent`
or `workflow-platform` when the same core request is being driven by another
product shell. Unknown ids fail before execution and show the known catalog
values.
Run results include an SDK-owned `outcome` such as `terminal` or `pending`,
plus `surfacePolicyAssessment` metadata so automation can see missing required
context and recommendations such as adding `--workspace`, `--harness`, or
`--workflow`.
Use `run --preflight` to return only that assessment without preparing a run.
The command exits with `0` when the selected surface is ready and `1` when
required context is missing.

`run` accepts exactly one prompt source: an inline prompt, `--prompt-file`, or
`--stdin`. Optional system prompts can be passed with `--system` or
`--system-file`, and are forwarded through the SDK into the core
`AgentRequest`.

`run --spec <file>` loads defaults from a versioned Java properties run spec.
Supported launch keys are `specVersion=1`, optional `profileId=<id>`, and
optional `requireReady=true`.
Request keys mirror the SDK request: `prompt`, `systemPrompt`, `tenantId`,
`modelId`, `workflowId`, `surfaceId`, `sessionId`, `userId`, `skills`,
`memoryEnabled`, `maxSteps`, `workspacePath`, `workspaceEnabled`,
`workspaceMaxEntries`, `harnessEnabled`, `harnessMaxChecks`,
`harnessIncludeOptional`, and `context.<key>`. Explicit CLI flags override spec
values, while repeatable `--context key=value` extends or overrides
`context.<key>` entries.
Use `--print-spec` to emit the resolved request as deterministic properties
without submitting a run. Add `--output <path>` to write that resolved spec to
a UTF-8 file; existing files are refused unless `--force` is provided.

`profileId` selects a reusable product profile such as `coding-agent`,
`openclaw-agent`, `assistant-agent`, `workflow-agent`, `low-code-agent`, or
`platform-admin`. `run --profile <id>` applies the same profile directly from
the CLI and overrides a spec's `profileId` when both are present. Explicit
request keys in the spec and CLI flags override profile defaults.
Use `profiles`, `profiles --surface <surface-id>`, or `profiles inspect <id>`
to discover these profile contracts in text or JSON.

`spec validate` parses a run spec and returns the same normalized preview used
by `run --dry-run`, exiting `0` when the selected surface is ready and `1` when
required context is missing. `spec template` prints a starter properties spec
for a product surface using the SDK-owned surface policy. Add
`--profile <id>` to print a starter spec from a product profile instead. Add
`--output <path>` to save the starter spec directly; existing files are refused
unless `--force` is provided.

`run --dry-run` resolves the prompt source, SDK defaults, product surface
policy, workspace context, harness plan, and core `AgentRequest` shape without
submitting a run. It exits with `0` when the selected surface is ready and `1`
when required context is missing. Use `--json` when CI, a coding-agent shell,
or a low-code node needs the normalized request envelope. Planning JSON for
`run --preflight` and `run --dry-run` includes a top-level `contract` object
with `schema=andalus.run.planning`, `version=1`, and an envelope such as
`run-preflight` or `run-preview`. The Draft 2020-12 schemas also describe the
readiness, surface policy assessment, skill assessment, normalized request,
context, and parameter fields that product shells bind before a run exists.
Use `contracts --json` to discover the SDK-owned JSON contract catalog for
agent shells; use `contracts --index --json` when a product only needs totals,
filters, facets, and command ids. Filter either shape with `--schema`,
`--envelope`, `--command-id`, `--domain`, or `--json-schema-id` when a product
needs one contract family, one envelope, the output contract for one command,
one product-facing contract domain, or one stable JSON Schema id. These
commands are also listed by
`commands --category Contracts` for product-shell command discovery.
Use `contracts --envelope <envelope> --schema-json` or
`contracts --json-schema-id <schema-id> --schema-json` to render the Draft
2020-12 JSON Schema document for one matching contract. Use
`contracts --schema-bundle-json` with any contract filter to render a bundle of
Draft 2020-12 JSON Schema documents for all matching contracts.
Schema-backed golden payload fixtures are validated against these published
schemas in CLI tests so output and contracts move together.
Regenerate source golden fixtures with
`mvn -q -pl andalus-aqli-cli -am -Dandalus.golden.update=true -Dtest=AndalusCliJsonContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
from the `andalus-aqli` module, then rerun the same test without the update
property to verify copied resources and schemas. Add
`-Dandalus.golden.update.include=<fixture>[,<fixture>]` to refresh only selected
fixtures; names may include or omit the `.golden` suffix.
Contract descriptors include stable `commandIds`, so shells can jump from an
envelope to matching command catalog entries.
Contract discovery JSON also includes aggregate `domains`, `domainCounts`,
`jsonSchemaIds`, `commandIds`, and `commandIdCounts` plus structured
`schemaSummaries`, `domainSummaries`, and `envelopeSummaries` for
product-shell filters and summary UIs. Each descriptor includes a stable
`jsonSchemaId` so shells can link directly from a contract entry to the schema
document.
JSON-producing command entries include a `contracts` array in
`commands --id <command-id> --json` when the command output has a stable
Andalus contract. Each command contract includes schema, version, envelope, and
`jsonSchemaId` references.
Platform catalog JSON is represented by `andalus.platform.catalog` envelopes
for `platform-status`, `product-catalog`, `profile-list`, and
`profile-detail`, so product shells can bind `status --json`,
`products --json`, `profiles --json`, and `profiles inspect ... --json`
without relying on display text.
Skill catalog JSON is represented by `andalus.skill.catalog` envelopes for
`skill-discovery` and `skill-detail`, so product shells can bind
`skills list/search --json` and `skills inspect ... --json` across built-in,
RAG, MCP, workflow, memory, and observability skill sources.
Command discovery JSON is itself represented by
`urn:andalus:contract:andalus.command.discovery:v1:commands-discovery`; use that
schema id to fetch the Draft 2020-12 schema or to list the `commands --json`
surfaces that share the envelope.
The full workbench JSON envelope is schema-backed as
`urn:andalus:contract:andalus.workbench.discovery:v1:workbench-discovery`; use
that schema id to bind platform status, product catalog, command query,
command palette, command entries, and next actions as one product-shell
surface.
Use `contracts --check --json` in CI to validate both directions; the command
exits nonzero if a command id or contract reference drifts.
Use `contracts --coverage --json` to see which published contracts are backed
by command ids and which are catalog/schema-only while an implementation is
still adapter-owned.

`run --session`, `run --user`, and repeatable `run --context key=value` provide
the shared run envelope for product shells. Coding agents can attach repo or
ticket ids, assistant products can attach conversation identity, workflow nodes
can attach node/run metadata, and RAG/MCP adapters can pass namespaced hints
without adding new CLI flags for every future integration.

`products` also shows each surface policy: preferred context families,
suggested skill families, and routing hints that future coding-agent,
assistant-agent, workflow, or admin shells can use without adding parallel
surface logic. Use `products --json` when another product shell or API adapter
needs the catalog as a stable machine-readable envelope.

`workspace`, `harness`, and `run` support `--json` for automation and product
integrations that need stable machine-readable envelopes instead of terminal
text.

---
name: add-feature
description: >-
  Step-by-step guide for adding a new feature to cf-sandbox-builder following
  the established Play Framework + Thymeleaf + Guice patterns. Use this skill
  when the user asks to implement a new endpoint, service method, or UI view.
---

# Skill: Adding a New Feature to cf-sandbox-builder

## Current Codebase State (Verified Aug 2026)

`SandboxController`, `InMemorySandboxService`, `SandboxInstance`, and `SandboxStatus` are **fully wired**, not empty stubs.
The service uses an in-memory `ConcurrentHashMap`. `build.sbt` has Guice, Thymeleaf, Postgres JDBC, Lombok — but NOT docker-java or Ebean yet.

---

## Sprint 1 Critical Path: Docker Socket Integration

### Step 1 — Add docker-java to `build.sbt`

```scala
"com.github.docker-java" % "docker-java-core" % "3.4.0",
"com.github.docker-java" % "docker-java-transport-httpclient5" % "3.4.0",
```

### Step 2 — Add `sandbox_instances` DB schema

```sql
CREATE TABLE sandbox_instances (
  id            VARCHAR(64) PRIMARY KEY,
  city_name     VARCHAR(255) NOT NULL,
  status        VARCHAR(32)  NOT NULL,
  pin           CHAR(6)      NOT NULL,
  container_id  VARCHAR(128),
  host_port     INTEGER,
  created_at    TIMESTAMP    NOT NULL,
  expires_at    TIMESTAMP    NOT NULL
);
```

### Step 3 — Extend `SandboxInstance` model

Add fields to `server/app/models/SandboxInstance.java` (Lombok `@Data @Builder`):
```java
private String pin;           // 6-digit string
private String containerId;   // Docker container ID
private int hostPort;         // assigned port (10000–11000 range)
```

### Step 4 — Implement `DockerSandboxService`

Create `server/app/services/DockerSandboxService.java`:

```java
@Singleton
public class DockerSandboxService implements SandboxService {

    private final DockerClient dockerClient;

    @Inject
    public DockerSandboxService() {
        this.dockerClient = DockerClientBuilder.getInstance()
            .withDockerCmdExecFactory(new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create("unix:///var/run/docker.sock"))
                .build())
            .build();
    }

    @Override
    public CompletionStage<SandboxInstance> createSandbox(String name, String version, ...) {
        return CompletableFuture.supplyAsync(() -> {
            String pin = generatePin();           // 6-digit random
            int port = allocatePort();            // next available in 10000–11000
            String image = "civiform/civiform:" + version;

            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withPortBindings(PortBinding.parse(port + ":9000"))
                .withNetworkMode("bridge")
                .exec();

            dockerClient.startContainerCmd(container.getId()).exec();

            // Persist to DB, return SandboxInstance with containerId, hostPort, pin
        });
    }
}
```

### Step 5 — Rebind in Guice (`MainModule.java`)

```java
bind(SandboxService.class).to(DockerSandboxService.class);
```

### Step 6 — Mount Docker socket in `docker-compose.yml`

```yaml
builder:
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
```

### Step 7 — PIN validation endpoint

Add to `server/conf/routes`:
```
POST /sandboxes/:id/access    controllers.SandboxController.validatePin(request: Request, id: String)
```

Controller method:
```java
public CompletionStage<Result> validatePin(Http.Request request, String id) {
    DynamicForm form = formFactory.form().bindFromRequest(request);
    String pin = form.get("pin");
    return sandboxService.validatePin(id, pin)
        .thenApply(sandbox -> sandbox
            .map(s -> redirect(s.getUrl()))
            .orElse(badRequest("Invalid PIN")));
}
```

---

## Pattern: Generic New Backend Feature

### 1. Service interface → add method to `SandboxService.java`

```java
CompletionStage<MyResult> myNewOperation(String param);
```

### 2. Implement in service class (stub then wire real logic)

```java
@Override
public CompletionStage<MyResult> myNewOperation(String param) {
    return CompletableFuture.completedFuture(/* stub */);
}
```

### 3. Add Controller method (async, never block)

```java
public CompletionStage<Result> myAction(Http.Request request, String id) {
    return sandboxService.myNewOperation(id)
        .thenApply(result -> ok(myView.render(result)));
}
```

### 4. Add Route to `server/conf/routes`

```
GET  /sandboxes/:id/my-action   controllers.SandboxController.myAction(request: Request, id: String)
```

### 5. Create the View

- **ViewModel**: `server/app/views/<feature>/<Feature>ViewModel.java` — Lombok `@Data @Builder`
- **View class**: `server/app/views/<feature>/<Feature>View.java` extends `BaseView`
- **Thymeleaf template**: `server/app/views/<feature>/<feature>.html`

### 6. Add UI Strings to `server/conf/messages`

```
sandbox.myfeature.title=My Feature Title
```

### 7. HTMX Polling Pattern (for async status)

Return a partial HTML fragment from a polling endpoint:
```java
public CompletionStage<Result> statusFragment(Http.Request request, String id) {
    return sandboxService.getSandbox(id).thenApply(maybeSandbox -> {
        // return partial Thymeleaf fragment, not full page
        return ok(statusView.renderFragment(maybeSandbox)).as("text/html");
    });
}
```

In the template:
```html
<div hx-get="/sandboxes/[[${sandbox.id}]]/status"
     hx-trigger="every 3s"
     hx-swap="outerHTML"
     th:if="${sandbox.status == 'PROVISIONING'}">
  Provisioning...
</div>
```

### 8. Write Tests

In `server/test/`, mirroring the `app/` package structure. Use JUnit 4 + AssertJ + Mockito.

---

## Sprint Mapping (PRD v2)

| Sprint | Key BE Milestone |
|---|---|
| **S1 (NOW)** | DB schema + Docker socket + SandboxService real impl + PIN gate |
| S2 | Replace Docker socket with AWS ECS Fargate |
| S3 | Isolated RDS Postgres per sandbox, seeding via ProgramMigrationService |
| S4 | Synthetic data seeder, program template library, Lead/CRM API |
| S5 | 30-day expiry (EventBridge), PIN gate BE, usage tracking |
| S6 | PDF Scaffolder integration, graduation flow, Discovery Engine wiring |
| S7 | City name injection, SMTP pre-config, Discovery seeder, security |
| S8 | Integration + load tests |

---

## Key CiviForm Integration Points

- **Program seeding**: Call `ProgramMigrationService.saveImportedProgram()` to load program JSON into a sandbox
- **City branding**: Inject `WHITELABEL_CIVIC_ENTITY_SHORT_NAME` env var at container launch (Sprint 2)
- **Admin export**: Use `AdminExportController` to export sandbox state for graduation flow
- **Fake auth**: Pre-configure `FakeAdminClient` + guest login in sandbox image for demos

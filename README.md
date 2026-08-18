# Civiform Sandbox Builder (`cf-sandbox-builder`)

A specialized tool and web platform to deploy, orchestrate, and manage on-demand demo and sandbox instances of [Civiform](https://github.com/civiform/civiform/).

---

## 🏛️ Architecture & Technology Stack

`cf-sandbox-builder` reuses Civiform's proven architecture, design patterns, and core dependencies:

- **Backend Framework**: [Play Framework 3.0 (Java)](https://www.playframework.com/) with [Google Guice](https://github.com/google/guice) for dependency injection.
- **Server-Side Templating**: [Thymeleaf](https://www.thymeleaf.org/) configured with Play Framework bindings and internationalization (`PlayMessageResolver`).
- **Design System & Styling**: [U.S. Web Design System (USWDS 3.x)](https://designsystem.digital.gov/) integrated alongside [Tailwind CSS](https://tailwindcss.com/) for utility styling.
- **Frontend Tooling**: [Vite](https://vitejs.dev/) bundler with TypeScript, Sass, PostCSS, and [HTMX](https://htmx.org/) for reactive interactions.
- **Containerization**: [Docker](https://www.docker.com/) multi-stage builds (`Dockerfile`, `prod.Dockerfile`) and [Docker Compose](https://docs.docker.com/compose/) orchestration with PostgreSQL.
- **Testing**: JUnit 4, AssertJ, and Mockito.

---

## 📁 Directory Structure

```
cf-sandbox-builder/
├── Dockerfile                  # Development container definition
├── prod.Dockerfile             # Production multi-stage release container
├── docker-compose.yml          # Base Docker Compose configuration (Postgres + Builder)
├── docker-compose.dev.yml      # Local development compose overrides (volume mounts)
├── init_postgres.sql           # Database initialization script
├── bin/                        # Developer CLI & management scripts
│   ├── build-dev               # Build local dev container image
│   ├── build-prod              # Build production release image
│   ├── run-dev                 # Start containerized dev environment
│   ├── stop-dev                # Stop running containers
│   ├── sbt                     # Run SBT commands inside the dev container
│   ├── npm                     # Run npm commands inside the dev container
│   └── lib.sh                  # Shared script helpers
└── server/                     # Play Framework Java application & frontend
    ├── build.sbt               # SBT build definition and JVM dependencies
    ├── package.json            # Node/frontend dependencies and build scripts
    ├── vite.config.mts         # Vite asset compilation and bundling pipeline
    ├── tailwind.config.js      # Tailwind CSS configuration
    ├── tsconfig.json           # TypeScript configuration
    ├── conf/
    │   ├── application.conf    # Main Play configuration and module bindings
    │   ├── application.dev.conf# Local development overrides
    │   ├── routes              # HTTP routing definitions
    │   ├── messages            # Localization / UI strings
    │   └── logback.xml         # Logging configuration
    ├── app/
    │   ├── controllers/        # Play MVC Controllers (HomeController, SandboxController, HealthCheck)
    │   ├── models/             # Data models & records (SandboxInstance, SandboxStatus)
    │   ├── modules/            # Guice Dependency Injection modules (ThymeleafModule, MainModule, ObjectMapperModule)
    │   ├── services/           # Business logic & orchestration services (SandboxService)
    │   ├── views/              # Thymeleaf BaseView, ViewModels, Layouts, and HTML templates
    │   └── assets/             # Frontend source assets (TypeScript, SCSS, Tailwind styles)
    └── public/
        └── dist/               # Compiled frontend bundles (CSS, JS, Fonts, Images)
```

---

## 🚀 Getting Started

### Prerequisites
- [Docker](https://docs.docker.com/get-docker/) & Docker Compose
- (Optional for host development): OpenJDK 21+, SBT 1.10+, Node.js 20+

### Running with Docker (Recommended)

1. **Start the local development stack:**
   ```bash
   ./bin/run-dev
   ```
   This will spin up PostgreSQL and the Sandbox Builder application.

2. **Access the application:**
   - Web UI Dashboard: [http://localhost:9000](http://localhost:9000)
   - Health Check: [http://localhost:9000/health](http://localhost:9000/health)

3. **Stop the stack:**
   ```bash
   ./bin/stop-dev
   ```

---

## 🛠️ Frontend & Assets Development

Frontend assets are bundled using Vite and compiled into `server/public/dist/`:

```bash
cd server

# Install frontend dependencies
npm install

# Compile assets for production
npm run build

# Watch mode during frontend development
npm run build:watch
```

---

## 🧪 Testing

Run unit tests via SBT:
```bash
./bin/sbt test
```
Or directly from `server/` if SBT is installed locally:
```bash
cd server && sbt test
```

---

## 🗺️ Roadmap & Next Steps (From TDD)
- [ ] Connect sandbox creation to container runtime orchestration (Docker / Kubernetes / Cloud Run).
- [ ] Integrate Civiform database auto-seeding with realistic test programs, questions, and sample applicants.
- [ ] Implement lifecycle management, auto-expiry timers, and teardown routines.
- [ ] Add authentication & RBAC for admin users deploying sandboxes.

# CLAUDE.md

## Project

IntelliJ IDEA plugin for AIF Handoff — embedded Kanban board for AI agent task management.

## Build

Java is NOT installed system-wide. Use the JDK found at:

```bash
JAVA_HOME="/Users/macos/.antigravity/extensions/redhat.java-1.12.0-darwin-x64/jre/17.0.4.1-macosx-x86_64" \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew buildPlugin
```

Output ZIP: `build/distributions/aif-handoff-plugin-1.0.0.zip`

Install via IDE: Settings → Plugins → ⚙️ → Install Plugin from Disk.

## Key files

- `src/main/kotlin/com/aifhandoff/plugin/AifToolWindowFactory.kt` — UI (toolbar, browser, settings, log panel)
- `src/main/kotlin/com/aifhandoff/plugin/AifDevServerService.kt` — server lifecycle, API calls, git operations
- `src/main/resources/META-INF/plugin.xml` — plugin registration
- `src/main/resources/icons/logo.svg` — tool window icon
- `src/main/resources/META-INF/pluginIcon.svg` / `pluginIcon_dark.svg` — marketplace icons

## API

The plugin talks to the local AIF Handoff server. API base URL: `http://localhost:{WEB_PORT}/api`
Default port: 5180 (configurable via `WEB_PORT` in `.env`).

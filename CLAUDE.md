### 1. Plan Mode Default
- Enter plan mode for ANY not-trivial task (3+ steps or architectural decisions)
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until the mistake rate drops
- Review lessons at session start for a project

### 3. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 4. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes. Don't overengineer
- Challenge your own work before presenting it

## Core Principles
- **Simplicity First**: Make every change as simple as possible. Impact minimal code
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards
- **Study**: When writing Spring Boot code, add inline comments starting with "// STUDY:" to explain concepts the developer should study further (e.g., unfamiliar annotations, patterns, or framework internals). This is a deliberate exception to the user-level "minimize comments in production code" rule, scoped to learning-oriented edits in this project.


## Project General Instructions

- Always use the latest versions of dependencies.
- The main service (`src/`) is a Java 17 Spring Boot application; the bot component (`bot/`) is written in Go.
- Always use Gradle for dependency management (`build.gradle`, `gradlew`).
- Always create test cases for the generated code, both positive and negative.
- Use `com.jirabot` as the group ID and base Java package.
- Do not use the Lombok library.
- Use semantic versioning for the project. Each time you generate a new version, bump the PATCH section of the version number.
- Generate the Docker Compose file to run all components used by the application.
- Update README.md each time you generate a new version.

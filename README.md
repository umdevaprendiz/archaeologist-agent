# Code Archaeologist

An **autonomous, 100% local** AI agent that finds the most problematic parts of a legacy Java system (6, 7 and 8), modernizes that code to Java 17/21 on separate branches, and proves, with tests it writes itself, that the system's behavior did not change.

> **Status:** early development. The build and the command-line interface are in place, but no agent has been implemented yet. See the [roadmap](#roadmap).

## The problem

Old Java systems pile up years of patches and have almost no tests. Nobody remembers why each decision was made, so nobody touches the code for fear of breaking production. Code review tools flag style issues, but they don't answer the question that matters: **where do we start, and how do we change it without breaking it?**

The Archaeologist doesn't critique syntax. It decides **where** to act based on evidence: it crosses Git history (what changes often, what produces bugs, who touches it) with the structure of the code (complexity, size). Then it delivers the change already protected by tests.

## How it works

Once started with a repository path, the agent runs the whole cycle with no human intervention:

| Step | What happens |
|---|---|
| 1. Reconnaissance | Detects the hardware; picks, downloads and calibrates the local model |
| 2. Excavation | Reads the Git history (default: 5 years): churn, bug-fix commits, authors per file |
| 3. Mapping | Measures the code: Java version, complexity, size, dependencies |
| 4. Prioritization | Scores the risk of each class and builds the target queue |
| 5. Protection | Generates characterization tests on the old code and confirms they pass |
| 6. Restoration | Modernizes the target on a new, isolated branch |
| 7. Verification | Runs the same tests on the new code; measures coverage and mutation score |
| 8. Record | Commits to the branch, writes the report and moves on to the next target |

When a test fails after a refactoring, the agent reads the error, fixes the code and tries again, up to a retry limit (e.g. 3). If it still fails, it discards the branch and records why. Every branch that survives has green tests.

### Safety limits

- **Never touches the main branch.** It works only on `archaeologist/*` branches, in a separate working copy. Merging is the team's decision.
- **Never edits the characterization tests** to make a refactoring pass. The Judge checks the hashes of the test files.
- **Builds and tests run in Docker with no network** (`--network none`), and the container gets nothing from your machine but a copy of the repository and the agent's own dependency cache.
- **Your private data stays private.** The agent never reads, copies or mounts your home folder or your credentials (`~/.m2/settings.xml`, `~/.gradle`, `~/.ssh`, `~/.aws`, `~/.git-credentials`...). Nothing it prints or writes shows your local paths, user name, machine name, e-mail address or secrets. Conduct tests check this on every build: they run the agent with a fake home folder full of planted credentials and fail if any of it shows up.
- **Repository content is data, never instructions.** Comments, strings and commit messages are never followed as orders (prompt injection protection).
- **Everything goes to an [audit log](#audit-log)**, so the team can understand each decision later.
- **Nothing leaves the machine.** The model runs locally through [Ollama](https://ollama.com). The only internet access is the initial model download, which can also be done ahead of time for air-gapped environments.

## Agents

Each agent is a package under `src/main/java/com/archaeologist/`. Each package's `package-info.java` describes its responsibility.

| Agent | Package | Responsibility | Uses LLM? |
|---|---|---|---|
| Scout | `scout` | Detects the hardware, picks and calibrates the model | Only for calibration |
| Excavator | `excavator` | Mines Git: churn, bug fixes, authors, temporal coupling | Optional (ambiguous commits) |
| Cartographer | `cartographer` | Measures the code: complexity, size, dependencies | No |
| Prioritization | `prioritization` | Scores risk and picks the targets | No |
| Guardian | `guardian` | Generates and runs the characterization tests | Yes |
| Restorer | `restorer` | Migrates with OpenRewrite and modernizes with the LLM | Yes |
| Judge | `judge` | Approves, sends back for fixes, or discards, using objective rules only | No |
| Chronicler | `chronicler` | Commits and reports | Only to summarize |

Support packages: `inference` (Ollama client), `orchestrator` (the graph that connects the agents, with state and checkpoints) and `audit` (the audit log every agent writes to).

## How the agent decides where to act

Files that change often **and** are complex concentrate bugs and maintenance cost. That's why the risk score is multiplicative: a file only ranks high if it has both.

```
risk = churn × complexity × (1 + w_fixes × fixes + w_authors × authors)
```

Each signal is normalized from 0 to 1 within the repository, and the weights are configurable. The final queue also considers **feasibility**: a target that doesn't fit in the model's context, or that hits the database in every method, goes to the back of the queue.

## Refactoring strategy

1. **One base migration branch** (`archaeologist/migrate-java21`): OpenRewrite migrates the build and the APIs of the whole repository. The Java version is set per module, not per class.
2. **Modernizations stacked on the base branch**: one refactoring per branch (Streams, switch expressions, pattern matching, text blocks, `var`), done with the LLM.

Keeping the two apart shows whether a failure came from the JDK change or from the refactoring. Pitfalls the Restorer must respect:

- Records are immutable and change the API (`getName()` becomes `name()`). They don't fit JPA entities or classes populated through setters.
- Streams can change execution order, exception handling and performance in hot loops.
- Reflection, serialization and custom `equals`/`hashCode` need extra care.

## Stack

| Layer | Tool |
|---|---|
| Language and build | Java 21, Spring Boot 4.1, Maven (with wrapper) |
| Command line | picocli |
| Git mining | `git log --numstat` |
| Code metrics | JavaParser |
| Deterministic migration | OpenRewrite (`rewrite-migrate-java`) |
| Local inference | Ollama, through Spring AI |
| Orchestration | LangGraph4j or a custom state machine (to be decided) |
| Generated tests | JUnit + Mockito |
| Test quality | JaCoCo (coverage) + PIT (mutation testing) |
| Isolation | Docker with no network (through Testcontainers) |

## Requirements

- JDK 21 or newer
- Git on the `PATH`
- Later: Docker and Ollama

Maven doesn't need to be installed: the project uses the Maven Wrapper (`mvnw`).

## Build and run

```bash
# Linux/macOS
./mvnw verify

# Windows
mvnw.cmd verify
```

This compiles the code, runs the tests and builds `target/archaeologist.jar`. Maven's own log names the folder you build in; add `-q` for a quiet build if you are going to share its output.

The same build runs on every push and pull request, on Linux, Windows and macOS with JDK 21, plus Linux with JDK 25 ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)). When a job fails, its plain-text test reports are kept for 7 days in the run's artifacts. The XML reports are not published: for a failed test they list the machine's system properties, such as its folders and user name.

### Commands

```bash
java -jar target/archaeologist.jar --help
java -jar target/archaeologist.jar hotspots <repo>   # rank the riskiest classes (read-only)
java -jar target/archaeologist.jar run <repo>        # full cycle, on archaeologist/* branches
java -jar target/archaeologist.jar scout             # detect the hardware and pick the model
```

`archaeologist help <command>` (or `<command> --help`) shows the details of each command. Add `--stacktrace` to see the full details of an unexpected error. Those details come straight from Java and may include local paths: check them before you share them.

The agents behind the commands are not implemented yet: for now, `hotspots` and `run` check that `<repo>` is a Git repository and create the run's output folder and audit log, and every command then stops with a "not implemented yet" message.

### Output

Each run of `hotspots` or `run` gets its own folder for its reports, state and logs, and prints where it is:

```
$ java -jar target/archaeologist.jar hotspots .
Run 20261105T221814Z-3f9a: output in archaeologist-output/20261105T221814Z-3f9a
```

The run id is the start time in UTC plus a random suffix, so run folders sort by date. The output folder is `archaeologist-output` in the current folder (setting `archaeologist.output.folder`). When you run the agent from inside the repository under analysis, the output folder lands there too, but Git ignores it: the agent creates it with a `.gitignore` that ignores everything in it, so the repository's `git status` stays clean and no report is committed by accident. If the output folder already exists, the agent leaves it as it is.

### Audit log

Each run keeps an audit log, `audit.jsonl` in its folder: every action and decision of every agent, one JSON object per line ([JSON Lines](https://jsonlines.org)), so you can find out later what the agent did and why.

```json
{"timestamp":"2026-11-07T22:13:34.485Z","agent":"orchestrator","action":"run-started","input":"hotspots on repository 'legacy-app'; Code Archaeologist 0.1.0 on Java 21.0.5+11-LTS; settings: history.years=5, risk-weights.fixes=1.0, risk-weights.authors=0.5, retry.max-attempts=3, quality.min-line-coverage-percent=80, quality.min-mutation-score-percent=60","outcome":"run 20261107T221334Z-4997 started"}
{"timestamp":"2026-11-07T22:13:34.489Z","agent":"orchestrator","action":"run-finished","input":"","outcome":"succeeded"}
```

| Field | Meaning |
|---|---|
| `timestamp` | When, in UTC, with milliseconds |
| `agent` | Who: `scout`, `excavator`, `cartographer`, `prioritization`, `guardian`, `restorer`, `judge`, `chronicler`, or `orchestrator` for the run itself |
| `action` | What, e.g. `run-started` or `verdict` |
| `input` | A short summary of what the action worked on (empty if nothing) |
| `outcome` | What came out of it, or why it failed |

The first event of a run records the command, the repository's folder name, the agent's version and the settings that steer its decisions. The last one records how the run ended. A run that was killed has no `run-finished` event.

The log is append-only: events are only ever added at the end, and each one is written to the file at once, so a crash loses at most the event being written.

Events often quote the repository (commit messages, class names), so the log is safe to read and share:

- **No local paths.** Absolute paths of the repository, the current folder and your home folder are written as `<repo>`, `.` and `~`, and settings that are paths are left out.
- **One event, one line, no surprises in a terminal.** Line breaks, terminal control codes and the characters that reorder text on screen are escaped (`\n`, `\u001B`, `\u202E`...). Accented letters stay as they are.
- **Summaries, not dumps.** Each text is cut at 1000 characters.

Any JSON Lines tool can read it, for example `jq 'select(.agent == "judge")' audit.jsonl`.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | The command failed (see the error message) |
| 2 | Invalid command line: unknown command, or a missing or invalid argument |
| 3 | The repository path does not exist, is not a folder, or is not a Git repository |
| 4 | Invalid configuration: a setting or the model catalog is missing, unknown or out of range |

Errors are printed as one readable line on standard error. Messages show paths exactly as typed, never expanded to absolute paths.

### Configuration

The defaults are bundled with the agent, in `src/main/resources/application.yml`, with a comment for each setting. Override any of them with an environment variable or a Java system property:

```bash
# Linux/macOS: environment variable (upper case, dots become underscores, dashes are dropped)
ARCHAEOLOGIST_HISTORY_YEARS=3 java -jar target/archaeologist.jar hotspots <repo>

# Windows (PowerShell)
$env:ARCHAEOLOGIST_HISTORY_YEARS = "3"; java -jar target/archaeologist.jar hotspots <repo>

# Any OS: system property
java -Darchaeologist.history.years=3 -jar target/archaeologist.jar hotspots <repo>
```

| Setting | Default | Meaning |
|---|---|---|
| `archaeologist.history.years` | `5` | Years of Git history the Excavator reads |
| `archaeologist.risk-weights.fixes` | `1.0` | Weight of the bug-fix commits in the risk score |
| `archaeologist.risk-weights.authors` | `0.5` | Weight of the number of distinct authors in the risk score |
| `archaeologist.retry.max-attempts` | `3` | Attempts per target, the first one included, before its branch is discarded |
| `archaeologist.quality.min-line-coverage-percent` | `80` | Minimum line coverage of the characterization tests on the target class |
| `archaeologist.quality.min-mutation-score-percent` | `60` | Minimum mutation score of the characterization tests on the target class |
| `archaeologist.output.folder` | `archaeologist-output` | Each run writes its reports, state and logs to its own subfolder of this folder |
| `archaeologist.model-catalog` | the bundled `config/models.yaml` | Model catalog to use instead, without rebuilding |

Settings are checked at startup. A value out of range, or an unknown setting (usually a typo, such as `ARCHAEOLOGIST_RETRY_MAXATEMPTS`), stops the agent with exit code 4 before it does any work. An `application.yml` in the current folder is never read: that folder may be the repository under analysis, and its files are data, not settings.

## Project structure

```
archaeologist-agent/
├── .github/workflows/ci.yml         # build and tests on Linux, Windows and macOS
├── config/
│   └── models.yaml                  # model catalog read by the Scout
├── src/
│   ├── main/java/com/archaeologist/
│   │   ├── ArchaeologistApplication.java
│   │   ├── cli/                     # archaeologist command and subcommands
│   │   ├── config/                  # typed settings and the model catalog
│   │   ├── run/                     # run ids and output folders
│   │   ├── audit/                   # the audit log of each run
│   │   ├── scout/
│   │   ├── excavator/
│   │   ├── cartographer/
│   │   ├── prioritization/
│   │   ├── guardian/
│   │   ├── restorer/
│   │   ├── judge/
│   │   ├── chronicler/
│   │   ├── inference/
│   │   └── orchestrator/
│   ├── main/resources/
│   │   └── application.yml          # default settings
│   └── test/java/com/archaeologist/
├── LICENSE                          # Apache License 2.0
├── mvnw, mvnw.cmd                   # Maven Wrapper
└── pom.xml
```

## Roadmap

The order starts with what delivers value without an LLM, and tackles the riskiest part (characterization tests) early.

| Phase | Deliverable | Guiding question |
|---|---|---|
| 1 | Excavator + Cartographer + Prioritization: a `hotspots <repo>` command produces the hotspot report | Does the ranking point at the classes the team already knows are trouble? |
| 2 | Guardian: characterization tests with a fixed local model | Can a local model generate tests that compile and pass on a real hotspot? |
| 3 | Restorer: base branch with OpenRewrite + modernizations with the LLM | When should a transformation be deterministic, and when is the LLM worth it? |
| 4 | Judge + Orchestrator with the retry loop and checkpoints | What state must be saved to resume an interrupted run? |
| 5 | Chronicler: reports | What does a developer need to read to trust a branch without reviewing it line by line? |
| 6 | Scout: hardware detection and automatic model selection | How do you reliably measure available memory on Linux, Windows and macOS? |

Until phase 6, the model is fixed in `config/models.yaml`.

The full breakdown into features, with IDs and dependencies, is in [docs/FEATURES.md](docs/FEATURES.md).

## Measuring success

- Percentage of targets refactored with all tests green
- Coverage and mutation score before and after, per module
- Reduction in cyclomatic complexity in the treated hotspots
- Percentage of code migrated to Java 17/21 APIs and features
- Average time per target on each machine profile

## License

Copyright 2026 the Code Archaeologist contributors.

Free to use, modify and distribute, including commercially, under the terms of the [Apache License 2.0](LICENSE).

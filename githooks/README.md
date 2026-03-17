# Git Hooks for This Project

This directory contains version-controlled git hooks for use with this repository.

## Available Hooks

- `pre-commit`: Runs the Tiger Spotless CLI and frontend linters on staged files, auto-fixing and re-staging only the
  previously staged files before each commit. This ensures all committed code is formatted and linted while preserving
  your working tree state.
- `pre-push`: Minimal hook that allows git push operations to proceed (currently a no-op).

## How to Install the Hooks

After cloning or pulling changes, run the following command from the project root:

```
sh githooks/setup-git-hooks.sh
```

This will copy the hooks to your local `.git/hooks/` directory and make them executable.

## Tiger Spotless CLI

The pre-commit hook uses a custom lightweight CLI wrapper around `spotless-lib` for fast formatting:

- **Fast execution**: No Maven startup overhead after the initial build
- **Selective staging**: Only re-stages files that were staged before the hook ran
- **Built automatically**: The CLI jar is built by the `tiger-spotless-cli` module during your Maven build

The first time the hook runs, it may build the Tiger Spotless CLI jar (one-time setup). Subsequent runs are nearly
instantaneous.

## Why?

Git does not track files in `.git/hooks/`. By versioning your hooks in `githooks/`, you ensure all team members use the
same checks and formatting. Each developer should run the setup script after cloning or when hooks are updated.

## Hook Behavior

- The `pre-commit` hook runs all formatting and linting steps without early exit
- If any step fails, the commit is blocked, but all steps still run so you can see all issues
- Only files that were staged before the hook runs get re-staged afterward
- Unstaged changes in your working tree are preserved

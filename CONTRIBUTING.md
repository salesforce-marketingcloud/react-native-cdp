# Contributing to @salesforce-data-cloud/react-native-cdp

Thank you for your interest in contributing! This project follows the
[Salesforce Open Source Community Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting Issues

Open an issue at
<https://github.com/salesforce-marketingcloud/react-native-cdp/issues>

Please include:

- Node.js version (`node --version`)
- React Native version
- Platform (iOS / Android / both)
- A minimal reproduction case or step-by-step description

## Pull Requests

1. Fork the repository and create your branch from `develop`.
2. Run the test suite before opening a PR: `npm test`
3. Run the linter: `npm run lint`
4. Run type checking: `npm run typecheck`
5. Follow the [Conventional Commits](https://www.conventionalcommits.org/) format
   for commit messages: `type(scope): subject`.
6. Add or update tests for any new behaviour or bug fix.
7. Open the PR against the `develop` branch; include a clear description of
   what changes and why.

### Commit Types

| Type       | When                                    |
| ---------- | --------------------------------------- |
| `feat`     | New feature or capability               |
| `fix`      | Bug fix                                 |
| `docs`     | Documentation only                      |
| `chore`    | Build system, config, CI                |
| `refactor` | Code restructuring, no behaviour change |
| `test`     | Adding or updating tests                |

## Developer Certificate of Origin (DCO)

By submitting a pull request you certify that you have the right to submit
the contribution under the Apache-2.0 license and that you agree to the
[Developer Certificate of Origin v1.1](https://developercertificate.org/).

## Code Style

- TypeScript/JavaScript: follow the project's ESLint configuration.
  Run `npm run lint -- --fix` before committing.
- Swift: follow [Swift API Design Guidelines](https://swift.org/documentation/api-design-guidelines/).
- Kotlin: follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).

## License

By contributing you agree that your contribution will be licensed under the
[Apache-2.0 License](LICENSE) that covers this project.

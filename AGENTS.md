# Repository contribution policy

- Branch new features as `feat/*` and open their PRs against `development`.
- Branch ordinary fixes as `fix/*` and open their PRs against `development`.
- Branch urgent production fixes as `hotfix/*` and open their PRs against `main`.
- Each `feat/*` or `fix/*` PR into `development`, and each `hotfix/*` PR into `main`, increments only the third versionName component: `x.y.(z+1)` relative to its destination.
- A promotion PR from `development` into `main` increments only the second component: `x.(y+1).z`. Do not reset the third component.
- Change the first component only when the repository owner explicitly requests it. Follow the owner-applied approval label procedure in `docs/ANDROID_RELEASES.md`.
- Increase the committed Android versionCode with every PR. Keep versionName changes in the PR for review, alongside the corresponding README version.
- Read `docs/ANDROID_RELEASES.md` for the workflow and run the version policy tests when changing it.
- Do not merge PRs on the owner's behalf unless explicitly asked; the owner reviews and merges them.

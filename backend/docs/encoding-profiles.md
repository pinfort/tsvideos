# Encoding profile rules

Apply `core/src/main/resources/db/migrations/073_encoding_profiles.sql` after migration 067 before deploying this version. Selections are retained with attempt history, including after reset. Existing attempts have no selection value.

No rules configured preserves the existing choice: AT-X (`ＡＴ－Ｘ`), duration strictly greater than 10800 seconds and title matching `#[0-9]{1,3}-#[0-9]{1,3}` uses `processor-tool.amatsukaze.atx-div-profile`; otherwise `default-profile` is used.

Configure rules in Spring configuration, for example an external `application.yml`:

```yaml
processor-profiles:
  fallback-profile: standard
  rules:
    - id: bs-long
      profile: high-quality
      channel: BS1
      min-duration-seconds: 1800
    - id: anime-title
      profile: anime
      title-pattern: 'アニメ|Animation'
```

Rules are evaluated in list order; the first match wins. All predicates on a rule must match. Supported predicates: `channel` and `channel-name` exact, case-sensitive matches; `title-pattern` a Java regular expression searched within the filename-derived title; `min-duration-seconds` and `max-duration-seconds` inclusive bounds using the existing ffprobe duration. No resolution or codec is inferred. Unknown/nonpositive/nonfinite duration cannot match a duration predicate. Rules without duration predicates may still match. Titles retain the existing filename-derived representation, including the extension. Keep administrator-provided patterns simple to avoid expensive backtracking.

Every rule requires a unique ID, nonblank profile, and at least one predicate. IDs use 1–64 ASCII letters/digits/dot/underscore/hyphen, beginning with a letter or digit. Configured duration bounds must be finite, positive and ordered. Invalid syntax or missing explicit `fallback-profile` fails configuration before processing side effects. The fallback applies when rules exist and none match; it does not override legacy behavior when the rules list is empty. Profile names are passed as individual arguments, including spaces/Japanese text. Their presence on the remote Amatsukaze server cannot be checked by the current client; configure an existing server profile.

`process --dry-run <recording>` reports selected profile, rule and reason without writing selection history or submitting a task. A real process stores one immutable selection after inspection and before registration/splitting/upload/submission, then sends that same profile. `history --job-id ID` and `history --job-id ID --json` expose `encodingProfile` (`profile`, `ruleId`, `reason`); reasons are MATCHED_RULE, FALLBACK, LEGACY_ATX_DIV or LEGACY_DEFAULT. Changing rules later does not change old selections. Timestamp and input identity belong to the associated immutable attempt history. This PR stacks on processing history; parallel processing and quality-rule pipeline changes require retaining this selection step when their independent stacks are integrated.

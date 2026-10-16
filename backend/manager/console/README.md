# Manager CLI output

Human-readable output remains the default. Read commands accept `--json` or
`--ids-only` (mutually exclusive). Run the executable jar directly when piping
output; Gradle itself writes build messages to stdout.

```sh
java -jar manager-console.jar search --name ニュース --json
java -jar manager-console.jar get program 123 --json
java -jar manager-console.jar get executed_file 456 --json
java -jar manager-console.jar duplicates --min-overlap 0.8 --json
java -jar manager-console.jar duplicates --ids-only
```

Machine stdout is UTF-8, without headings, colors or diagnostics. Logging and
errors go to stderr. JSON is a single object with integer `schemaVersion: 1`
and the payload below. Errors leave machine stdout empty; consume it only when
the process exits successfully. `--help` and `--version` retain their ordinary
informational text behavior.

| Command | Payload |
| --- | --- |
| `search --json` | `programs`: array of program objects with `tsExists`; boolean `truncated` |
| `get program ID --json` | `program`: program object with `createdFiles`, `tags`, `checks` |
| `get executed_file ID --json` | `executedFile`: executed-file object |
| `duplicates --json` | `groups`: array of duplicate group objects |

Program fields: `id`, `executedFileId`, `drops`, `size` are integers; `duration`
is a number in seconds; `name`, `status`, `recordedAt`, `channel`, `title`,
`channelName` are strings. `size` is bytes. `status` is `REGISTERED`, `COMPLETED`,
or `ERROR`. IDs and byte counts are 64-bit integers; consumers must preserve
their precision. `recordedAt` is ISO local date-time (`yyyy-MM-ddTHH:mm:ss`, seconds always present, fractional seconds only when non-zero) without an offset, using
the existing recording environment's local time convention (normally Japan
time); it is not converted to UTC. `tsExists` is a boolean indicating registered
TS metadata, not a live NAS integrity check.

`tags` is a string array of detection tags on the recording (`ews`,
`superimpose`); `checks` is a string array of detectors that have run on it
(`emergency_broadcast`). A tag absent from `tags` means "not detected" only when
its detector is listed in `checks`; otherwise the recording was never checked.

`createdFiles` contains objects with integer `id`, `splittedFileId`, `size`
(bytes), string `file`, nullable strings `mime`, `encoding`, and string `status`
(`REGISTERED`, `ENCODE_SUCCESS`, `FILE_MOVED`). Helper properties such as `isTs`
are not included. `executedFile` has integer `id`, `drops`, `size`, numeric
`duration`, strings `file`, `recordedAt`, `channel`, `title`, `channelName`,
and `status` (`REGISTERED`, `DROPCHECKED`, `SPLITTED`).

Each duplicate group has string `reason` (`SAME_BROADCAST` or `OVERLAP`), integer
`recommendedId`, integer array `candidateIds` (the other members), and `programs`
in recommendation order, each with `tsExists`.

`--ids-only` prints one program ID per line. Search emits results ordered by
recording time then ID; get emits the requested program ID. Duplicates emits
**all members, including recommended keepers**, deduplicated and sorted by ID;
it is not a deletion-candidate list. `get executed_file --ids-only` is invalid
because an executed-file ID is not a program ID. Search returns at most 500
programs. When more match, JSON sets `truncated: true` and every mode prints a
warning to stderr (stdout is unaffected); narrow the name to get the rest.

| Exit | Meaning |
| --- | --- |
| 0 | Success, including empty search/duplicate results (empty arrays or no ID lines) |
| 1 | Operational failure; sanitized diagnostic on stderr (the exception class name is logged, never its message) |
| 2 | Invalid input, unsupported option combination or invalid positive ID |
| 3 | Specifically requested program/executed file does not exist |

The missing-resource exit applies to human mode too. Read commands perform no
mutations. Partial-batch and integrity status codes are reserved for commands
that support those operations.

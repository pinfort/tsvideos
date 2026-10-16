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
and the payload below. Read-command errors leave machine stdout empty; consume
read results only when the process exits successfully. Doctor diagnostic failures
instead return the complete report with exit 1. `--help` and `--version` retain their ordinary
informational text behavior.

| Command | Payload |
| --- | --- |
| `search --json` | `programs`: array of program objects with `tsExists` |
| `get program ID --json` | `program`: program object with `createdFiles` |
| `get executed_file ID --json` | `executedFile`: executed-file object |
| `duplicates --json` | `groups`: array of duplicate group objects |
| `doctor --json` | `checks`: array of diagnostic checks, including failures |

Program fields: `id`, `executedFileId`, `drops`, `size` are integers; `duration`
is a number in seconds; `name`, `status`, `recordedAt`, `channel`, `title`,
`channelName` are strings. `size` is bytes. `status` is `REGISTERED`, `COMPLETED`,
or `ERROR`. IDs and byte counts are 64-bit integers; consumers must preserve
their precision. `recordedAt` is ISO local date-time without an offset, using
the existing recording environment's local time convention (normally Japan
time); it is not converted to UTC. `tsExists` is a boolean indicating registered
TS metadata, not a live NAS integrity check.

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
because an executed-file ID is not a program ID. Search retains the existing
500-result limit.

| Exit | Meaning |
| --- | --- |
| 0 | Success, including empty search/duplicate results (empty arrays or no ID lines) |
| 1 | Operational failure; sanitized diagnostic on stderr |
| 2 | Invalid input, unsupported option combination or invalid positive ID |
| 3 | Specifically requested program/executed file does not exist |

The missing-resource exit applies to human mode too. Read commands perform no
mutations. Partial-batch and integrity status codes are reserved for commands
that support those operations.

## Environment diagnostics

```sh
java -jar manager/console/build/libs/console.jar doctor
java -jar manager/console/build/libs/console.jar doctor --json
java -jar manager/console/build/libs/console.jar doctor --check-write --json
java -jar manager/console/build/libs/console.jar --spring.profiles.active=local doctor --json
```

Doctor validates each required database/NAS setting before attempting connections.
It loads the same Boot application configuration, core/console/infrastructure
profiles, environment variables and external profile overrides as the manager,
but creates no Spring application context, datasource pool or normal command graph.
Malformed URLs and unavailable databases therefore remain diagnosable. Spring
configuration options use `--spring.property=value` and may precede or follow
`doctor`. `doctor --help` requires no valid configuration or network access.

Each check has an `id`, `status` (`PASS`, `FAIL`, `UNCHECKED`) and an actionable
`reason`. Configuration values, usernames, passwords, URLs and raw exceptions are
never printed. Doctor suppresses library logging in its own process so driver
messages cannot leak credentials. It does not change logging in normal commands.

Database diagnostics use a separate connection whose only statement is `SELECT 1`
(the connection is also flagged read-only as a driver hint), with 3-second
connect/socket/query timeouts that replace any timeouts in the configured URL. An
empty database password is accepted for passwordless users; authentication is
verified by the connectivity check. NAS diagnostics use SMB2/3 with 3-second
connection, response and socket timeouts and one request retry. These are per-I/O
bounds, not a total command deadline; OS hostname resolution and multiple checks
can add time. Both NAS stores are checked even if another endpoint fails.

The default checks existence and directory listing permission on each configured
NAS base directory, without reading recordings or writing any library records.
An empty base directory selects the share root; absolute paths, parent-directory
traversal, URL escape/query/fragment characters and control characters are invalid.
NAS write checks are explicitly `UNCHECKED` by default.
`--check-write` exclusively creates a UUID-named `.tsvideos-doctor-*.tmp` file under
each base directory, writes and reads a small marker, and deletes its own probe
in a `finally` block even if verification fails. It never overwrites a recording
or creates a base directory. Cleanup failure is a separate `FAIL` naming only the
temporary filename for manual removal. A creation failure does not authorize
deleting an existing file; cleanup is then `UNCHECKED`, with the probe name provided
for inspection if the server accepted a create request before the connection failed.

Exit `0` means no failed checks (optional unchecked write probes are allowed),
`1` means one or more diagnostics failed, and `2` means invalid CLI input. JSON
diagnostic reports remain available on stdout with exit `1`; a configuration-file
loading failure produces a sanitized `config.load` check. Doctor does not accept
`--ids-only` because its results are infrastructure checks, not program IDs.

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
| `search --json` | `programs`: array of program objects with `tsExists`; integer `total`, `limit`, `offset` |
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

`--ids-only` prints one program ID per line. Search emits the requested page in
its chosen sort order; get emits the requested program ID. Duplicates emits
**all members, including recommended keepers**, deduplicated and sorted by ID;
it is not a deletion-candidate list. `get executed_file --ids-only` is invalid
because an executed-file ID is not a program ID.

| Exit | Meaning |
| --- | --- |
| 0 | Success, including empty search/duplicate results (empty arrays or no ID lines) |
| 1 | Operational failure; sanitized diagnostic on stderr (the exception class name is logged, never its message) |
| 2 | Invalid input, unsupported option combination or invalid positive ID |
| 3 | Specifically requested program/executed file does not exist |

The missing-resource exit applies to human mode too. Read commands perform no
mutations. Partial-batch and integrity status codes are reserved for commands
that support those operations.

## Search filters and pagination

All filters are optional and combine with AND. `search` without filters lists
the newest recordings. Existing `search -n NAME` syntax remains supported.

| Option | Meaning |
| --- | --- |
| `-n`, `--name` | Case-insensitive literal substring of program name; `%` and `_` are ordinary characters |
| `--channel` | Exact channel identifier, not the display channel name |
| `--recorded-from` | Inclusive ISO local date or date-time, e.g. `2026-10-01` or `2026-10-01T06:00:00`; a date means its midnight |
| `--recorded-to` | Exclusive ISO local date or date-time; a date means its midnight, so pass the next day to include a whole day |
| `--status` | `registered`, `completed`, `error` |
| `--min-drops`, `--max-drops` | Inclusive nonnegative dropped-frame limits; unknown drops (`-1`) never match a numeric limit |
| `--file-type` | `ts` or `encoded`, as defined below |
| `--sort` | `recorded-at` (default), `duration`, `size` |
| `--direction` | `desc` (default) or `asc`; program ID breaks ties in the same direction |
| `--limit` | Page size, 1–500; default 100 |
| `--offset` | Matching programs to skip; default 0, must be nonnegative |

Date bounds must be ordered (`from < to`); drop limits must be ordered
(`min <= max`). Dates retain the recording environment's local time convention.
Sorting by size/duration uses original executed-file metadata (bytes/seconds),
not aggregate generated-file size.
Programs whose original metadata is missing remain searchable by name/status
or without filters. SQL null sort values precede known values in ascending
order and follow them in descending order, with the same ID tie-break. They
do not match date/channel/drop/file-type filters. Existing domain fallback
values remain unchanged in output (`drops: -1`, `duration: -1`, `size: 0`,
empty strings, and the minimum local date-time).

File filters require a registered created file in `FILE_MOVED` state. `ts`
matches MIME `video/vnd.dlna.mpeg-tts`. `encoded` matches `video/*` except that
TS MIME; null MIME and ancillary files do not qualify. A program with both
types matches either filter, and multiple matching files do not duplicate a
program or inflate totals. This is metadata availability, not a live NAS probe.

JSON retains `schemaVersion: 1` and its `programs` array, adding `total` (all
matches before pagination), `limit`, and `offset`. Human output ends with the
same counts. ID-only output contains only the selected page's IDs; increment
`--offset` by `--limit` to fetch later pages (or use JSON's `total`). Empty or
beyond-end pages succeed.

Compatibility: search used to return up to 500 programs in one response; the
default page is now 100, so pass `--limit 500` or page with `--offset` to get
more. The short-lived JSON `truncated` flag is replaced by `total`; a page is
incomplete when `offset + programs.length < total`. Separate page requests can reflect library changes;
the stable tie-break avoids ambiguous ordering within each snapshot.

```sh
java -jar manager-console.jar search --channel BS1 --status completed --max-drops 0 --json
java -jar manager-console.jar search --recorded-from 2026-10-01T00:00:00 --recorded-to 2026-11-01T00:00:00 --file-type encoded --sort size --direction desc --limit 50 --offset 50 --json
```

The shared backend API is `ProgramCommand.search(ProgramSearchQuery)` returning
`ProgramSearchPage(items, total, limit, offset)`. Validation, filtering, and sort
semantics live in core for the web interface to reuse. The older
`selectByName` method is retained for existing callers; new search callers
should use the shared paged API.

This is the frontend for tsvideos, built with [Next.js](https://nextjs.org).

## Getting Started

Install dependencies and run the development server:

```bash
pnpm install
pnpm dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fonts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Vercel.

## Configuration

`NEXT_PUBLIC_API_BASE_URL` — base URL of the `manager:api` backend. Defaults to `http://localhost:8080` if unset, so no `.env` file is needed to run against a locally running backend on its default port. This must be a browser-reachable URL, since the video playback/download page fetches video bytes directly from the backend rather than through the Next.js server.

## Recording search

The home page combines a literal name substring, exact channel ID, local
recording timestamps, processing status, drop-count bounds and available file
type. All fields are optional. The end timestamp is exclusive; for a whole day
choose the next midnight. Timestamps use the recording environment's local
time without timezone conversion. Numeric drop filters exclude unknown drops.
TS/encoded availability uses `FILE_MOVED` file metadata, not a live NAS check;
encoded means `video/*` except `video/vnd.dlna.mpeg-tts`.

Sorting supports recording time, original duration and original byte size in
either direction, with a stable program-ID tie-break. Search defaults to 10
items per page. Filters, sorting and page size are kept in the URL and restored
on reload; submitting the form resets the offset to zero. Invalid conditions
show an editable error instead of silently changing the query. Empty results
and service failures have separate messages.

`GET /api/v1/programs` accepts `name`, `channel`, `recordedFrom`, `recordedTo`,
`status`, `minDrops`, `maxDrops`, `fileType`, `sort`, `direction`, `limit`, and
`offset`, and returns `{ programs, total, limit, offset }`. Enum values are
uppercase: `REGISTERED|COMPLETED|ERROR`, `TS|ENCODED`,
`RECORDED_AT|DURATION|SIZE`, `ASC|DESC`. The API defaults to limit 100, offset 0,
recorded-time descending; limit must be 1–500. Invalid values or reversed
bounds return HTTP 400. Existing name-only queries still work. Query semantics
are shared with the manager CLI's `ProgramSearchQuery` in core.

## Development scripts

- `pnpm dev` - start the development server
- `pnpm build` - build for production
- `pnpm start` - run the production build
- `pnpm lint` - run ESLint
- `pnpm typecheck` - run TypeScript type checking
- `pnpm test` - run the test suite
- `pnpm test:watch` - run the test suite in watch mode
- `pnpm coverage` - run the test suite with coverage report

## Learn More

To learn more about Next.js, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.

The `/duplicates` page compares the existing CLI duplicate groups and recommendation order. Selection starts empty; cleanup uses the same server actions and preview/confirmation UI as bulk library management. Saved failure results remain visible even when refreshing removes the last duplicate group. FILE_MOVED file listings are metadata, not a new NAS verification.

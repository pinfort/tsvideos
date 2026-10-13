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

## Scripts

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

## Browser-local playback history

The responsive player supports 0.5–2× speed and a restart button. Position is stored per video in this browser's localStorage (`tsvideos.playback.v1`), at most every five seconds during playback and on pause, seek, page hide, or navigation. No account/server storage or cross-device synchronization is used. Up to 50 unfinished videos are kept; clearing browser data removes this history. Disabled or malformed storage does not prevent playback.

A video is complete at 95% or within its final 10 seconds; completed and restarted entries are removed. Saved positions beyond a newly shortened duration restart at zero. The home page offers continue-watching links, checks known program/file metadata, distinguishes deleted files from unavailable checks, and allows history removal. Direct video links without program metadata remain usable and are labeled unchecked. Media bytes still stream directly from the configured backend URL.

Continue-watching metadata checks use a same-origin Next.js route to avoid requiring cross-origin browser API access. Only program/file JSON is proxied; media streaming remains direct. API failures remain unchecked, while confirmed HTTP 404 means removed.

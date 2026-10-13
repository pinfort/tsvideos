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

The `/health` dashboard shows failed recordings, dropped frames and saved file verification. Its explicit scan button invokes read-only NAS checks; page/state reload only reads saved results. Browser requests use `/api/library-health` on the same origin, which calls the backend through `lib/api/health`. `NEXT_PUBLIC_API_BASE_URL` must also be reachable by the Next server. Apply the backend library-health migration before use. Unknown, inaccessible and confirmed-missing states are displayed separately with UTC verification times; there is no automatic repair or deletion.

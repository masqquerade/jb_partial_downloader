# Parallel File Downloader

A file downloader written in Kotlin that fetches chunks of a file in parallel using HTTP Range requests. Falls back to sequential downloading when the server doesn't support partial content.

# How it works

```
                         HEAD request
                         
                              │
                              ▼
                    ┌───────────────────┐
                    │  Check server for │
                    │  Accept-Ranges &  │
                    │  Content-Length   │
                    └────────┬──────────┘
                             │
                 ┌───────────┴───────────┐
                 │                       │
          Ranges supported        Not supported
                 │                       │
                 ▼                       ▼
        Split into chunks;       Sequential download
       Download in parallel       
                 │                       │
                 ▼                       ▼
          Combine chunks           Write to disk
       via RandomAccessFile
                 │                       │
                 └───────────┬───────────┘
                             ▼
                     Create output file
```

# Overview

The downloader sends a `HEAD` request to determine whether the server supports range requests. If `Accept-Ranges: bytes` and a valid `Content-Length` are present, the file gets split into chunks (5 MB by default) and downloaded concurrently using Kotlin coroutines. A `Semaphore(4)` limits the number of simultaneous connections. Each chunk is written at its correct byte offset using `RandomAccessFile`, so chunks can arrive in any order.

If the server doesn't support ranges, the downloader falls back to a simple sequential download.

Both paths use a `.tmp` file during the process - if something fails, the temp file gets cleaned up. Every download attempt has retry logic with exponential backoff (up to 3 retries by default).

### Tech stack

- **Kotlin**
- **Ktor**
- **Ktor MockEngine** for unit tests
- **JUnit 5**

# Getting started

### 1. Clone the repository

```
git clone https://github.com/masqquerade/jb_partial_downloader.git
cd jb_partial_downloader
```

### 2. Start a local file server

You need a web server that supports range requests. The easiest way is Apache via Docker:

```
docker run --rm -p 8080:80 -v /path/to/your/local/directory:/usr/local/apache2/htdocs/ httpd:latest
```

Place any file you want to download into that directory.

### 3. Create .env file in root of the repository

The downloader reads two environment variables:

```
SERVER_URL=http://localhost:8080/
FILENAME=test.txt
```

### 4. Run

```
./gradlew run
```

The downloaded file will appear as `downloaded_file.txt` in the project root.

### Running tests

```
./gradlew test
```

Tests use Ktor's `MockEngine` — no running server required.

# Project structure

```
src/
├── main/kotlin/
│   ├── Main.kt       # Downloader logic, entry point
│   └── Models.kt     # ServerInfo data class
└── test/kotlin/
    └── DownloaderTest.kt
```

| Function | Description |
|---|---|
| `checkCapabilities` | Sends HEAD request, parses `Accept-Ranges` and `Content-Length` |
| `calculateRanges` | Splits total content length into byte ranges |
| `downloadChunk` | Downloads a single range with retry and exponential backoff |
| `downloadFileParallel` | Orchestrates parallel chunk downloads via coroutines |
| `downloadFileSeq` | Sequential fallback when ranges aren't supported |
| `downloadFileSeqWithRetry` | Wraps sequential download with retry logic |

# TrueUp for Java

The official client for the [TrueUp API](https://trueup-cloud.merchantprotocol.workers.dev/docs). Send TrueUp two ledgers (a supplier statement and your receiving log, your books and the bank feed, invoices and payments) and it pairs every row, then tells you what's only on one side, what was counted twice and where the numbers disagree.

Java 11+. One dependency (Gson).

## Install

Maven:

```xml
<dependency>
  <groupId>io.github.merchantprotocol</groupId>
  <artifactId>trueup</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation "io.github.merchantprotocol:trueup:0.1.0"
```

## Quickstart

Create an API key in the TrueUp dashboard (**API keys**), then set `TRUEUP_API_KEY`:

```java
import io.github.merchantprotocol.trueup.Models.Finding;
import io.github.merchantprotocol.trueup.Models.ReconcileResult;
import io.github.merchantprotocol.trueup.Table;
import io.github.merchantprotocol.trueup.TrueUp;

TrueUp trueup = TrueUp.builder().build(); // reads TRUEUP_API_KEY

// left: the side that bills or claims; right: the other side
ReconcileResult result = trueup.reconcile(Table.file("statement.csv"), Table.file("receiving.csv"));

System.out.println(result.headline);
// 7 of 8 rows of statement.csv paired with receiving.csv; 1 only in statement.csv, ...
for (Finding f : result.findings) {
    System.out.println(f.kind + " " + f.subject + " " + f.detail + " " + f.amount);
}
// qty_mismatch statement.csv:row 5 Qty 24 vs qty_received 20; ... 99.6
// phantom statement.csv:row 6 no match on the other side 43.2
```

## Reconcile

A table is a file, file contents, or rows:

```java
Table.file("books.csv");
Table.content("books.csv", csvText);
Table.rows("invoices", List.of(Map.of("Invoice #", "INV-10101", "Date", "2026-06-09", "Total", "$2,999.31")));
```

CSV, TSV, JSON and JSON Lines are read, and date and number formats are detected. Nothing about the columns is configured.

Not sure which file is which? `trueup.reconcileFiles(List.of(Table.file("a.csv"), Table.file("b.csv")), null)` picks the pair and the sides.

**Reuse what was learned** by passing an earlier result's weights, and **answer the questions** TrueUp wasn't sure about:

```java
ReconcileResult march = trueup.reconcile(Table.file("march-statement.csv"), Table.file("march-receiving.csv"));
ReconcileResult april = trueup.reconcile(Table.file("april-statement.csv"), Table.file("april-receiving.csv"),
    new ReconcileOptions().weights(march.details.weights));

trueup.reconcile(Table.file("statement.csv"), Table.file("receiving.csv"), new ReconcileOptions().answers(
    new Answers(List.of(List.of("statement.csv:row 12", "receiving.csv:row 11")),
                List.of(List.of("statement.csv:row 3", "receiving.csv:row 9")))));
```

Each call to `reconcile` or `reconcileFiles` counts as one analysis on your plan.

## Match

Two lists that describe the same things in different words (two catalogs, a supplier's price book and your invoice, two vendor lists): every record on the left is paired with its counterpart on the right, or reported as having none. Nothing is configured; the columns can have different names.

```java
MatchResult result = trueup.match(Table.file("invoice.csv"), Table.file("catalog.csv"));
System.out.println(result.headline);
// 4 of 5 records in invoice.csv matched to catalog.csv (0 unsure); 1 have no counterpart.
for (Models.Finding f : result.findings) System.out.println(f.kind + " " + f.subject + " " + f.detail);
// match 4 ~ 5 4 · cheese puffs jumbo 8oz · 3.30 · 10  ↔  C-105 · Cheese Puffs Jumbo 8 oz · 3.25
// only_left 5 5 · beef jerky teriyaki 2.5oz · 5.75 · 6
```

`kind` is `match`, `unsure_match` (a person should check), `only_left` or `only_right`. `details.pairs` lists `[left id, right id, confidence]`. Like `reconcile`, it takes `Table.file`, `Table.content` or `Table.rows`; `matchFiles` picks the pair; `matchStored` works on stored files (with a saved model); and `details.weights` can be passed back to `match(left, right, weights)` to match next month's lists the same way. One analysis per call.

## Audit

Find what doesn't add up. Send text documents with labeled amounts (invoices, statements, schedules; about 4 or more of a kind) and TrueUp learns the arithmetic each kind obeys from the documents themselves, then flags the ones that break it. Send one table and it checks its rows the same way (qty × unit price = amount), and flags repeated rows.

```java
AuditResult result = trueup.audit(List.of(Table.file("inv-1041.txt"), /* … */ Table.file("inv-1046.txt")));
System.out.println(result.headline);
// 1 of 6 documents don't add up; 0 more to review (5 laws learned).
for (Models.Finding f : result.findings) System.out.println(f.subject + " " + f.amount + " " + f.detail);
// inv-1045.txt 200.0 subtotal + tax amount = total: 4,837.84 vs 5,037.84

// Next month, even one invoice at a time, against the same laws:
trueup.audit(List.of(Table.file("inv-1050.txt")), result.details.weights);
```

`auditStored(fileIds, model)` audits stored files. One analysis per call.

## Stored files, runs and saved models

Files uploaded to your team stay there (you'll also see them in the dashboard). Runs on stored files are kept, and what a run learned can be saved as a model:

```java
List<StoredFile> files = trueup.uploadFiles(Table.file("statement.csv"), Table.file("receiving.csv"));
StoredFile statement = files.get(0), receiving = files.get(1);   // .rows, .columns, .roles ("Qty" -> "number", ...)

ReconcileResult result = trueup.reconcileStored(statement.id, receiving.id);
String modelId = trueup.createModel(result.run_id, "Acme statements");

// Next month: apply what was learned.
trueup.reconcileStored(List.of(aprilStatement.id, aprilReceiving.id), modelId, null);
```

| Method | Returns |
|---|---|
| `uploadFiles(Table...)`, `listFiles()`, `getFile(id)` | `StoredFile`: `id`, `name`, `rows`, `columns`, `roles` |
| `fileContent(id)` | the bytes, exactly as uploaded |
| `deleteFile(id)` | |
| `reconcileStored(leftId, rightId)`, `reconcileStored(fileIds, model, answers)` | a result with `run_id` (one analysis) |
| `listRuns(limit, before)` | `RunPage`: `runs`, `has_more`, newest first |
| `forEachRun(action)` | every run, paging for you |
| `getRun(id)` | `RunDetail`: `run`, `result` |
| `createModel(runId, name)`, `listModels()`, `getModel(id)`, `deleteModel(id)` | `getModel` includes the `weights` |

## Findings

| `kind` | Meaning |
|---|---|
| `phantom` | Only on the left: billed or recorded, never matched |
| `unbilled` | Only on the right: received or paid, never billed |
| `duplicate`, `received_duplicate` | A copy of a row that's already paired |
| `qty_mismatch`, `price_change`, `amount_mismatch` | Paired rows whose numbers disagree |
| `unsure_pair` | A likely pair a person should confirm |

## Account and usage

```java
trueup.account();  // team, plan, key
trueup.usage();    // period, metrics (used, included, remaining, ...)
trueup.plans();
```

## Errors

Every error is a `TrueUpException` (unchecked) with `getStatus()` and `getCode()` (the API's error code):

| Class | When |
|---|---|
| `TrueUpException.AuthenticationException` | 401: missing, unknown or revoked key |
| `TrueUpException.InvalidRequestException` | 400, 413, 415, 422: the request or the files need fixing (`unsupported_file`, `not_reconcilable`, ...) |
| `TrueUpException.RateLimitException` | 429 `rate_limited`: retried automatically; `getRetryAfter()` seconds |
| `TrueUpException.QuotaExceededException` | 429 `quota_exceeded`: the plan's monthly allowance is used up |
| `TrueUpException.ServerException` | 5xx: retried automatically |
| `TrueUpException.ConnectionException` | the API couldn't be reached |

## Configuration

```java
TrueUp.builder()
    .apiKey("tu_live_...")           // default: TRUEUP_API_KEY
    .baseUrl("https://...")          // default: TRUEUP_BASE_URL, then the hosted API
    .timeout(Duration.ofMinutes(5))  // per request
    .maxRetries(2)                   // rate limits, 5xx and dropped connections
    .build();
```

## Development

The tests run in Docker against the live API:

```bash
export TRUEUP_API_KEY=tu_live_...   # a key for a test team (each run uses 8 analyses)
just test                            # or: docker compose run --rm test
```

## License

MIT

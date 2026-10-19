---
type: regex
target: { source: file, path: PriceFetcher.java }
pattern: '^(?!\s*//).*(newFixedThreadPool|newCachedThreadPool|newWorkStealingPool|new\s+ThreadPoolExecutor|new\s+ForkJoinPool)'
flags: im
match: not_contains
---

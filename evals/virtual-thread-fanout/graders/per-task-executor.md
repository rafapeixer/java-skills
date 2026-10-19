---
type: regex
target: { source: file, path: PriceFetcher.java }
pattern: 'try\s*\(\s*(var|ExecutorService)\s+\w+\s*=\s*Executors\.newVirtualThreadPerTaskExecutor\s*\('
match: contains
---

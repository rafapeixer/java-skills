#!/usr/bin/env bash
# Every scenario in BENCHMARKS.md for the given implementations, one repetition:
#   bash benchmarks/orders-api/reps.sh r1 java-without java-with java-tuned java-with-v2 java-with-v3
# Uses the harness from go-skills (same spec, same load generator, same limits):
#   HARNESS=../go-skills/benchmarks/orders-api/harness
# Results land in $HARNESS/results/<scenario>-<impl>-<rep>/; aggregate them with
#   cd $HARNESS/analyze && go run . ../results
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
harness=${HARNESS:-$here/../../../go-skills/benchmarks/orders-api/harness}
rep=$1
shift

ramp="200:20s,400:20s,800:20s,1200:20s,1600:20s,2000:20s,2500:20s,3000:20s"
high="3000:20s,4000:20s,5000:20s,6000:20s,8000:20s,10000:20s"
warm="500:30s,1500:30s,3000:30s,4000:20s,5000:20s,6000:20s,8000:20s"

cd "$harness"
for impl in "$@"; do
  dir=$here/impl/$impl
  bash run.sh "$dir" "s1-capacity-$impl-$rep" 1 "$ramp"
  bash run.sh "$dir" "s2-8replicas-$impl-$rep" 8 "2000:60s"
  bash run.sh "$dir" "s3-overload-$impl-$rep" 1 "800:20s,4000:30s,800:30s"
  bash run.sh "$dir" "s4-slowdep-$impl-$rep" 1 "400:40s" 1500
  bash run.sh "$dir" "s5-high-$impl-$rep" 1 "$high"
  bash run.sh "$dir" "s7-warm-high-$impl-$rep" 1 "$warm"
done

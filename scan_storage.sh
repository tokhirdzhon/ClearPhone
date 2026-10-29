#!/system/bin/sh
ROOT=/storage/emulated/0
TMP=/data/local/tmp
ALL=$TMP/sizes_all.txt
SIZES=$TMP/sizes_user.txt
CAND=$TMP/dup_candidates.txt
HASH=$TMP/hashes.txt

find "$ROOT" -type f -size +256k 2>/dev/null -exec stat -c '%s|%n' {} + > "$ALL"
find "$ROOT" -path "$ROOT/Android" -prune -o -type f -size +256k -exec stat -c '%s|%n' {} + 2>/dev/null > "$SIZES"

sort -t'|' -k1,1nr "$ALL" | head -40 > $TMP/large_files.txt

awk -F'|' '{c[$1]++} END {for (s in c) if (c[s]>1) print s}' "$SIZES" | sort -n > $TMP/dup_sizes.txt

awk -F'|' 'NR==FNR{d[$1]=1; next} ($1 in d){print $2}' $TMP/dup_sizes.txt "$SIZES" > "$CAND"

: > "$HASH"
while IFS= read -r f; do
  [ -f "$f" ] || continue
  h=$(md5sum "$f" 2>/dev/null | cut -c1-32)
  [ -n "$h" ] || continue
  sz=$(stat -c '%s' "$f" 2>/dev/null)
  echo "$h|$sz|$f" >> "$HASH"
done < "$CAND"

awk -F'|' '{c[$1]++; s[$1]=$2; p[$1]=p[$1]"\n    "$3} END {for (h in c) if (c[h]>1) printf "%d|%d|%d%s\n", s[h]*(c[h]-1), s[h], c[h], p[h]}' "$HASH" | sort -rn > $TMP/dup_groups.txt

awk -F'|' '{w+=$1; g++} END {printf "wasted_bytes=%d\ngroups=%d\n", w, g}' $TMP/dup_groups.txt > $TMP/dup_summary.txt

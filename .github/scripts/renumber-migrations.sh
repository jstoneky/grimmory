#!/usr/bin/env bash
# Detects Flyway version collisions after a merge and renumbers our custom
# migrations to sit sequentially above the highest upstream version.
set -euo pipefail

MIGRATION_DIR="backend/src/main/resources/db/migration"

# All migration filenames present in upstream/main.
# -r is required: without it ls-tree returns the directory entry itself.
upstream_files=$(git ls-tree -r upstream/main "$MIGRATION_DIR" --name-only \
  | grep -oP 'V\d+__[^/]+\.sql' || true)

# Highest version number in upstream
max_upstream=$(echo "$upstream_files" \
  | grep -oP '(?<=V)\d+' | sort -n | tail -1 || echo "0")
max_upstream=${max_upstream:-0}

# Our custom migrations: any V*.sql on disk that upstream does not have
our_migrations=()
while IFS= read -r -d '' f; do
  filename=$(basename "$f")
  if ! echo "$upstream_files" | grep -qF "$filename"; then
    our_migrations+=("$f")
  fi
done < <(find "$MIGRATION_DIR" -maxdepth 1 -name 'V*.sql' -print0 | sort -zV)

if [ ${#our_migrations[@]} -eq 0 ]; then
  echo "No custom migrations found — nothing to renumber."
  exit 0
fi

# Check whether any of our migrations collide with an upstream version number
needs_renumber=false
for f in "${our_migrations[@]}"; do
  ver=$(basename "$f" | grep -oP '(?<=V)\d+')
  if echo "$upstream_files" | grep -qP "^V${ver}__"; then
    needs_renumber=true
    break
  fi
done

if ! $needs_renumber; then
  echo "No Flyway version collisions detected — nothing to renumber."
  exit 0
fi

# Always anchor custom migrations at V900+ so they never collide with upstream
# in practice (upstream would need ~750 more releases to reach this range).
CUSTOM_BASE=900
floor=$(( max_upstream > CUSTOM_BASE ? max_upstream : CUSTOM_BASE ))

# Never renumber a migration that has already been published: renaming a
# migration Flyway has applied on user databases breaks their schema history.
# origin/<branch> reflects the last pushed (and therefore published) state.
published_ref="origin/feature/acquisition"
for f in "${our_migrations[@]}"; do
  if git cat-file -e "${published_ref}:${f}" 2>/dev/null; then
    echo "ERROR: collision detected, but $(basename "$f") is already published on ${published_ref}." >&2
    echo "Renumbering it would corrupt deployed Flyway histories — resolve manually." >&2
    exit 1
  fi
done

echo "Collision detected. Renumbering custom migrations above V${floor}..."

next=$((floor + 1))
for f in "${our_migrations[@]}"; do
  filename=$(basename "$f")
  suffix="${filename#V*__}"          # everything after V<n>__
  new_name="V${next}__${suffix}"
  new_path="$MIGRATION_DIR/$new_name"
  if [ "$f" != "$new_path" ]; then
    echo "  $filename  →  $new_name"
    git mv "$f" "$new_path"
  fi
  next=$((next + 1))
done

git add "$MIGRATION_DIR"
echo "Done. Custom migrations renumbered starting at V$((floor + 1))."

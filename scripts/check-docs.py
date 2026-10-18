#!/usr/bin/env python3
"""Check local documentation links and capability records without network access."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
errors = []
docs = [p for p in root.rglob('*.md') if not any(x in p.parts for x in ('build', '.gradle', '.git'))]
for document in docs:
    content = document.read_text()
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', content):
        if re.match(r'^(https?://|mailto:|#)', target):
            continue
        target = target.split('#')[0]
        if not (document.parent / target).exists():
            errors.append(f'{document.relative_to(root)}: missing link {target}')
for document in (root / 'capabilities').glob('*.md'):
    if document.name == 'index.md':
        continue
    content = document.read_text()
    for field in ('ID', '状态', '默认提供方', '前提', '切换条件', '契约', '依赖与版本', '验证', '替代方案'):
        if not re.search(r'^' + re.escape(field) + r':\s*\S', content, re.M):
            errors.append(f'{document.relative_to(root)}: missing {field}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print(f'Checked {len(docs)} Markdown files and capability records.')

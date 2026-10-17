"""Export a diagnostic fluid icon index from Huiji Wiki's public API or XML exports.

Online: python tools/generate-ae-fluid-icons.py --online
Offline: python tools/generate-ae-fluid-icons.py export.xml

Online updates discover the selected version's fluid data pages, read their
registry-to-filename metadata, and check those exact files against allimages.
Only JSON metadata is downloaded; pictures and game-local fluid IDs are unused.
Responses are cached for 24 hours. --refresh bypasses that cache.
Source: https://gtnh.huijiwiki.com/wiki/Project:GTNHDumper
"""
import argparse
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

API = 'https://gtnh.huijiwiki.com/api.php'
FILE_PREFIX = 'Icon_gregtech_gt.GregTech_FluidDisplay_'
ICON = re.compile(r'icon_gregtech_gt[.]GregTech_FluidDisplay_(\d+_[A-Za-z0-9]+)[.]png')


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    try:
        temporary.write_text(content, encoding='utf-8', newline='\n')
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


class WikiAPI:
    def __init__(self, cache_dir, refresh=False):
        self.cache_dir, self.refresh = cache_dir, refresh
        self.requests = self.hits = 0
        self.last_request = 0

    def __call__(self, **params):
        params = dict(action='query', format='json', formatversion=2, maxlag=5, **params)
        query = urllib.parse.urlencode(sorted(params.items()))
        cache = self.cache_dir / (hashlib.sha256(query.encode()).hexdigest() + '.json')
        if not self.refresh and cache.exists() and 0 <= time.time() - cache.stat().st_mtime < 86400:
            data = json.loads(cache.read_text(encoding='utf-8'))
            self.hits += 1
        else:
            # One sequential request per second at most; no image downloads.
            time.sleep(max(0, 1 - (time.monotonic() - self.last_request)))
            self.last_request = time.monotonic()
            request = urllib.request.Request(API + '?' + query, headers={
                'User-Agent': 'MPY4OC-fluid-index/1.0 (read-only development tool)',
                'Accept': 'application/json',
            })
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(32 * 1024 * 1024 + 1)
            if len(raw) > 32 * 1024 * 1024:
                raise ValueError('Wiki response exceeds 32 MiB')
            data = json.loads(raw)
            if not isinstance(data, dict) or 'error' in data or 'warnings' in data:
                raise ValueError('Wiki API error/warning: ' + str(data)[:500])
            atomic_write(cache, json.dumps(data, ensure_ascii=False))
            self.requests += 1
        if not isinstance(data, dict) or 'error' in data or 'warnings' in data:
            raise ValueError('Invalid cached Wiki response')
        return data


def paginated(api, key, **params):
    continuation, seen = {}, set()
    for unused in range(100):
        data = api(**params, **continuation)
        rows = data.get('query', {}).get(key)
        if not isinstance(rows, list):
            raise ValueError('Missing Wiki result: ' + key)
        yield from rows
        continuation = data.get('continue')
        if not continuation:
            return
        marker = json.dumps(continuation, sort_keys=True)
        if marker in seen:
            raise ValueError('Wiki pagination repeated a continuation token')
        seen.add(marker)
    raise ValueError('Wiki pagination exceeded 100 pages; refusing partial results')


def online_pages(api, version):
    if not re.fullmatch(r'GTNH/[A-Za-z0-9_.-]+', version):
        raise ValueError('Version must look like GTNH/2.9.0-RC-1')
    prefix = 'Data:Versions/' + version + '/Items/'
    found = list(paginated(api, 'search', list='search',
        srsearch='"mFluidDisplayHeat" prefix:' + prefix, srlimit=50, srprop=''))
    titles = sorted({row['title'] for row in found})
    if not titles or any(not title.startswith(prefix) for title in titles):
        raise ValueError('No fluid data pages found for ' + version)
    pages, revisions = [], {}
    print('Discovered', len(titles), 'fluid data pages for', version, flush=True)
    for offset in range(0, len(titles), 2):
        wanted = titles[offset:offset + 2]
        data = api(prop='revisions', titles='|'.join(wanted), rvprop='ids|content', rvslots='main')
        received = set()
        for page in data.get('query', {}).get('pages', []):
            if 'missing' in page or page.get('title') not in wanted:
                raise ValueError('Missing or unexpected Wiki data page')
            revision = page['revisions'][0]
            pages.append((page['title'], json.loads(revision['slots']['main']['content'])))
            revisions[page['title']] = revision['revid']
            received.add(page['title'])
        if received != set(wanted):
            raise ValueError('Incomplete Wiki data page response')
    return pages, revisions


def xml_pages(exports):
    for source in exports:
        for page in ET.parse(source).getroot().findall('{*}page'):
            title = page.findtext('{*}title') or ''
            if title.startswith('Data:Versions/') and '/Items/' in title:
                yield title, json.loads(page.findtext('{*}revision/{*}text'))


def collect_icons(pages):
    icons, sources = {}, set()
    for title, document in pages:
        for row in document['items']:
            if not row['key'].startswith('fluid.'):
                continue
            name, match = row['key'][6:], ICON.fullmatch(row.get('iconName', ''))
            if not re.fullmatch(r"[ A-Za-z0-9_.:,()'-]+", name) or not match:
                raise ValueError('Unsupported fluid record: ' + row['key'])
            value = match[1]
            if name in icons and icons[name] != value:
                raise ValueError('Conflicting fluid versions: ' + name)
            icons[name] = value
            sources.add(title)
    return icons, sorted(sources)


def verify_files(api, icons):
    files = {}
    count = 0
    for row in paginated(api, 'allimages', list='allimages', aiprefix=FILE_PREFIX,
                         ailimit=500, aiprop='url|timestamp'):
        count += 1
        name = row['name']
        if re.fullmatch(re.escape(FILE_PREFIX) + r'\d+_[A-Za-z0-9]+[.]png', name):
            files[name] = row['url']
        if count % 500 == 0:
            print('Listed', count, 'file records (including animations and older versions)', flush=True)
    missing = [name for name, value in icons.items() if FILE_PREFIX + value + '.png' not in files]
    if missing:
        raise ValueError('Wiki files missing for ' + str(len(missing)) + ' fluids: ' + ', '.join(missing[:12]))
    # Match by the versioned metadata's exact filename, never first/latest hash match.
    resolved = {name: {'file': FILE_PREFIX + value + '.png', 'url': files[FILE_PREFIX + value + '.png']}
                for name, value in sorted(icons.items())}
    return resolved, count


def check_complete(icons):
    if len(icons) < 1000 or not all(name in icons for name in ('water', 'lava', 'oxygen', 'steam', 'molten.iron')):
        raise ValueError('Incomplete data: refusing to replace the diagnostic export')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('exports', type=Path, nargs='*')
    parser.add_argument('--online', action='store_true', help='Fetch public Wiki metadata and file index')
    parser.add_argument('--version', default='GTNH/2.9.0-RC-1')
    parser.add_argument('--cache-dir', type=Path, default=Path('build/wiki-fluid-icons'))
    parser.add_argument('--refresh', action='store_true', help='Ignore the 24-hour metadata cache')
    parser.add_argument('--output', type=Path, default=Path('build/wiki-fluid-icons/resolved-index.json'), help='Diagnostic JSON export; never changes the dashboard')
    args = parser.parse_args(argv)
    if args.online == bool(args.exports):
        parser.error('Choose --online OR one or more XML exports')
    if args.refresh and not args.online:
        parser.error('--refresh requires --online')
    if args.online:
        api = WikiAPI(args.cache_dir, args.refresh)
        pages, revisions = online_pages(api, args.version)
        icons, sources = collect_icons(pages)
        # Check completeness before requesting the file catalogue.
        check_complete(icons)
        resolved, count = verify_files(api, icons)
        manifest = {'version': args.version, 'revisions': revisions, 'file_records': count, 'fluids': resolved}
        print('Verified', len(icons), 'fluids;', count, 'file records;', api.requests, 'requests;', api.hits, 'cache hits', flush=True)
    else:
        icons, sources = collect_icons(xml_pages(args.exports))
        check_complete(icons)
        manifest = {'sources': sources, 'fluids': {name: {'file': FILE_PREFIX + value + '.png'} for name, value in sorted(icons.items())}}
    atomic_write(args.output, json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print('Exported', len(icons), 'fluid icons to', args.output)



if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        raise SystemExit('Export failed; existing output unchanged: ' + str(error))

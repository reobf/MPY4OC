"""Regression checks for the public-Wiki index updater; no network needed."""
import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fluid_icons', Path(__file__).with_name('generate-ae-fluid-icons.py'))
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)


class FluidIndexTest(unittest.TestCase):
    def test_pagination_keeps_exact_version_and_ignores_hash_aliases(self):
        prefix = icons.FILE_PREFIX
        replies = [
            {'query': {'allimages': [
                {'name': prefix + '8_shared.png', 'url': 'other-fluid'},
                {'name': prefix + '9_old.png', 'url': 'old-version'},
                {'name': prefix + '9_shared_dynamic.png', 'url': 'animation'},
            ]}, 'continue': {'aicontinue': 'next', 'continue': '-||'}},
            {'query': {'allimages': [{'name': prefix + '9_shared.png', 'url': 'correct'}]}},
        ]
        calls = []
        def api(**params):
            calls.append(params)
            return replies.pop(0)
        resolved, count = icons.verify_files(api, {'test-fluid': '9_shared'})
        self.assertEqual(count, 4)
        self.assertEqual(resolved['test-fluid']['url'], 'correct')
        self.assertEqual(calls[1]['aicontinue'], 'next')
        self.assertEqual(calls[1]['continue'], '-||')

    def test_missing_exact_file_is_not_replaced_by_same_hash(self):
        def api(**params):
            return {'query': {'allimages': [{'name': icons.FILE_PREFIX + '8_shared.png', 'url': 'wrong'}]}}
        with self.assertRaisesRegex(ValueError, 'files missing'):
            icons.verify_files(api, {'test-fluid': '9_shared'})

    def test_broken_pagination_does_not_loop_forever(self):
        def api(**params):
            return {'query': {'allimages': []}, 'continue': {'aicontinue': 'same'}}
        with self.assertRaisesRegex(ValueError, 'repeated'):
            list(icons.paginated(api, 'allimages'))

    def test_conflicting_versions_are_rejected(self):
        pages = [(title, {'items': [{'key': 'fluid.oxygen',
            'iconName': 'icon_gregtech_gt.GregTech_FluidDisplay_' + value + '.png'}]})
            for title, value in [('old', '1_abc'), ('new', '2_xyz')]]
        with self.assertRaisesRegex(ValueError, 'Conflicting fluid versions'):
            icons.collect_icons(pages)

    def test_partial_online_update_preserves_existing_target(self):
        values = {str(i): str(i) + '_test' for i in range(1000)}
        values.update({name: '1_test' for name in ('water', 'lava', 'oxygen', 'steam', 'molten.iron')})
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'resolved-index.json'
            target.write_text('existing diagnostic export', encoding='utf-8')
            with patch.object(icons, 'online_pages', return_value=([], {})), \
                 patch.object(icons, 'collect_icons', return_value=(values, ['source'])), \
                 patch.object(icons, 'verify_files', side_effect=ValueError('incomplete catalogue')):
                with self.assertRaisesRegex(ValueError, 'incomplete catalogue'):
                    icons.main(['--online', '--output', str(target), '--cache-dir', directory])
            self.assertEqual(target.read_text(encoding='utf-8'), 'existing diagnostic export')

    def test_fresh_cache_avoids_network_and_refresh_bypasses_it(self):
        data = {'query': {'allimages': []}}
        payload = json.dumps(data).encode()
        with tempfile.TemporaryDirectory() as directory:
            client = icons.WikiAPI(Path(directory))
            with patch.object(icons.urllib.request, 'urlopen', return_value=io.BytesIO(payload)) as request:
                self.assertEqual(client(list='allimages'), data)
                self.assertEqual(client(list='allimages'), data)
                self.assertEqual(request.call_count, 1)
            self.assertEqual((client.requests, client.hits), (1, 1))
            with patch.object(icons.urllib.request, 'urlopen', return_value=io.BytesIO(payload)) as request:
                self.assertEqual(icons.WikiAPI(Path(directory), refresh=True)(list='allimages'), data)
                self.assertEqual(request.call_count, 1)


if __name__ == '__main__':
    with redirect_stdout(io.StringIO()):
        unittest.main()

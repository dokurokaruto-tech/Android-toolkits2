import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.library_index import LibraryIndex


class LibraryIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.date = '2026-10-16'
        self.folder = self.root / self.date
        self.folder.mkdir()
        for i in range(125):
            file = self.folder / f'{i:04d}.png'
            file.write_bytes(b'fixture')
            os.utime(file, ns=(1_000_000 + i, 1_000_000 + i))
        self.index = LibraryIndex(self.root / 'index.sqlite', self.root)

    def tearDown(self):
        self.index.close()
        self.temp.cleanup()

    def ready(self):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            page = self.index.page(self.date, 60)
            if not page['indexing']:
                return page
            time.sleep(0.01)
        self.fail('index did not finish')

    def test_first_scan_no_throttle(self):
        started = threading.Event()
        with patch('generation_agent.library_index.time.monotonic', return_value=1), patch.object(self.index, '_scan', side_effect=lambda _: started.set()):
            self.index.refresh(self.date)
            self.assertTrue(started.wait(1))

    def test_keyset_pages(self):
        page = self.ready()
        names = []
        while True:
            self.assertLessEqual(len(page['names']), 60)
            names.extend(page['names'])
            if page['next_cursor'] is None:
                break
            page = self.index.page(self.date, 60, page['next_cursor'])
        self.assertEqual(len(names), 125)
        self.assertEqual(len(set(names)), 125)
        self.assertEqual(names, sorted(names, reverse=True))

    def test_midstream_changes(self):
        first = self.ready()
        newer = self.folder / 'new.png'
        newer.write_bytes(b'new')
        self.index.upsert(self.date, newer)
        deleted = first['names'][0]
        (self.folder / deleted).unlink()
        self.index.remove(self.date, deleted)
        second = self.index.page(self.date, 60, first['next_cursor'])
        self.assertEqual(second['names'][0], '0064.png')
        self.assertNotIn('new.png', second['names'])
        self.assertFalse(set(first['names']) & set(second['names']))

    def test_persisted_page_no_scan(self):
        self.ready()
        self.index.close()
        self.index = LibraryIndex(self.root / 'index.sqlite', self.root)
        with patch.object(self.index, 'refresh'), patch('os.scandir', side_effect=AssertionError('page scanned directory')):
            self.assertEqual(len(self.index.page(self.date, 60)['names']), 60)

    def test_indexing_does_not_block(self):
        release = threading.Event()
        with patch.object(self.index, '_scan', side_effect=lambda _: release.wait(5)):
            page = self.index.page(self.date, 60)
            self.assertTrue(page['indexing'])
            self.assertEqual(page['names'], [])
            release.set()

    def test_reject_invalid_requests(self):
        for date, size, cursor in [('../other', 60, ''), (self.date, 201, ''), (self.date, 0, ''), (self.date, 60, 'bad')]:
            with self.assertRaises(ValueError):
                self.index.page(date, size, cursor)
        page = self.ready()
        with self.assertRaises(ValueError):
            self.index.page('2026-10-15', 60, page['next_cursor'])

    def test_nonimages_skipped(self):
        (self.folder / 'metadata.json').write_text('{}')
        (self.folder / 'fake.png').mkdir()
        self.ready()
        self.assertEqual(self.index.summary(self.date)[0], 125)

"""Source contracts, not device performance tests."""
from pathlib import Path
import unittest

SRC = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/kennys_dokidoki_wallpaper'

def code(name):
    return (SRC / (name + '.kt')).read_text()

class LoadingDesignTest(unittest.TestCase):
    def test_open_before_fetch(self):
        body = code('GeneratedFolderPickerActivity').split('private fun openRemoteFolder(')[1].split('private fun openLocalFolder')[0]
        self.assertNotIn('fetchImages(', body)
        self.assertNotIn('seedGeneratedSource(', body)

    def test_local_open_no_scan(self):
        body = code('GeneratedFolderPickerActivity').split('private fun openLocalFolder(')[1].split('private fun openAlbum')[0]
        self.assertNotIn('listFiles()', body)

    def test_no_progressive_viewer(self):
        self.assertNotIn('ProgressiveOriginalLoader.load(', code('FullScreenImageActivity'))

    def test_remote_disk_enabled(self):
        self.assertNotIn('DiskCacheStrategy.NONE', code('ImageStoragePolicy'))

    def test_ram_pressure_keeps_disk(self):
        self.assertNotIn('clearDiskCache()', code('ImageMemoryGovernor'))

    def test_decode_keeps_disk(self):
        self.assertNotIn('model.delete()', code('FullScreenImageActivity'))

    def test_memory_clear_keeps_jobs(self):
        body = code('OriginalImageMemoryCache').split('fun clear()')[1].split('fun entryCount')[0]
        self.assertNotIn('it.cancel()', body)

if __name__ == '__main__':
    unittest.main()

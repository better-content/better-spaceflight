"""Lightweight resource regression guards, NOT Minecraft loading/transfer proof."""
import gzip
import json
from pathlib import Path
import struct
import unittest


RESOURCES = Path(__file__).resolve().parents[1] / "src/probe/resources"


class ProbeResourcesTest(unittest.TestCase):
    def test_probe_has_its_own_pack_metadata(self):
        # Forge treats the probe source set as a distinct mod resource pack.
        # Missing metadata excluded its dimension AND template in native attempt 1.
        metadata = json.loads((RESOURCES / "pack.mcmeta").read_text())
        self.assertEqual(15, metadata["pack"]["pack_format"])
        self.assertTrue(metadata["pack"]["description"])

    def test_empty_template_header_size_and_air_palette(self):
        template = RESOURCES / "data/better_spaceflight_probe/structures/empty.nbt"
        data = gzip.decompress(template.read_bytes())
        self.assertTrue(data.startswith(b"\x0a\x00\x00"))  # unnamed root compound
        self.assertIn(b"\x03\x00\x0bDataVersion" + struct.pack(">i", 3465), data)
        self.assertIn(b"\x09\x00\x04size\x03" + struct.pack(">iiii", 3, 8, 8, 8), data)
        self.assertIn(b"\x08\x00\x04Name\x00\x0dminecraft:air", data)
        self.assertTrue(data.endswith(b"\x00"))
        # These are narrow encoding guards; only Minecraft can prove loading.

    def test_probe_dimension_matches_home_section_layout(self):
        dimension = json.loads((RESOURCES / "data/better_spaceflight/dimension/space.json").read_text())
        self.assertEqual("better_spaceflight:space", dimension["type"])
        dimension_type = json.loads((RESOURCES / "data/better_spaceflight/dimension_type/space.json").read_text())
        self.assertEqual(-64, dimension_type["min_y"])
        self.assertEqual(384, dimension_type["height"])


if __name__ == "__main__":
    unittest.main()

"""Keep every MainActivity layout compatible with its required view bindings."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


RESOURCES = Path(__file__).resolve().parents[1] / "MiracastReceiver/app/src/main/res"
ANDROID_ID = "{http://schemas.android.com/apk/res/android}id"


class MainActivityLayoutTest(unittest.TestCase):
    def test_all_layout_variants_provide_required_views(self):
        # Missing these views made MainActivity crash on landscape TV layouts.
        required_views = {
            "tv_device_name": "TextView",
            "tv_device_ip": "TextView",
            "tv_status": "TextView",
            "tv_miracast_status": "TextView",
            "startup_progress": "ProgressBar",
            "switch_auto_start": "androidx.appcompat.widget.SwitchCompat",
            "switch_auto_rotate": "androidx.appcompat.widget.SwitchCompat",
            "switch_return_to_app": "androidx.appcompat.widget.SwitchCompat",
            "tv_language_value": "TextView",
            "setting_language": None,  # MainActivity only requires a View.
        }
        layouts = sorted(RESOURCES.glob("layout*/activity_main.xml"))
        self.assertTrue(layouts, "No MainActivity layouts were found")
        for layout in layouts:
            with self.subTest(layout=layout.parent.name):
                views = {}
                for view in ET.parse(layout).getroot().iter():
                    if ANDROID_ID in view.attrib:
                        view_id = view.attrib[ANDROID_ID].split("/", 1)[-1]
                        self.assertNotIn(view_id, views, f"Duplicate view ID {view_id}")
                        views[view_id] = view.tag
                missing = sorted(required_views.keys() - views.keys())
                self.assertEqual([], missing, "Missing required MainActivity views")
                for view_id, expected_type in required_views.items():
                    if expected_type is not None:
                        self.assertEqual(expected_type, views[view_id], view_id)


if __name__ == "__main__":
    unittest.main()

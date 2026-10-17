"""Copy a built Scholix worker and adapters into the existing Feldman Home hub."""
import argparse
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("hub", type=Path)
parser.add_argument("--openu-source", type=Path, required=True, help="Checkout of oferfeldman/openu-api")
args = parser.parse_args()
root = Path(__file__).resolve().parent
repo = root.parent
gateway = args.hub.resolve() / "gateway"
if not (gateway / "app.py").is_file() or not (gateway / "openu.py").is_file():
    parser.error("Target must be the existing Feldman Home hub")
libs = root / "native/build/install/scholix-provider-worker/lib"
if not libs.is_dir(): parser.error("Run prepare.py and Gradle installDist first")
for name in ("scholix.py", "scholix_browser.py", "scholix_console.py", "scholix_google.py", "openu_portal.py", "openu_downloads.py"):
    shutil.copy2(root / name, gateway / name)
shutil.copy2(root / "openu-worker.cjs", gateway / "openu-client/worker.cjs")
shutil.copytree(libs, gateway / "scholix-client/lib", dirs_exist_ok=True)
shutil.copy2(repo / "tools/lemida/lemida.py", gateway / "scholix_lemida.py")
data = args.openu_source / "src/data"
target = gateway / "openu-data"
target.mkdir(exist_ok=True)
for name in ("courses.json", "degrees_index.json"): shutil.copy2(data / name, target / name)
shutil.copytree(data / "degrees", target / "degrees", dirs_exist_ok=True)
print("Exported Scholix adapters, JVM provider worker and the OpenU website's planner catalogs.")

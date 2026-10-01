"""Print per-class JUnit results so CI logs show exactly which tests ran."""
import glob
import sys
import xml.etree.ElementTree as ET

total = failed = skipped = 0
for path in sorted(glob.glob(sys.argv[1] + "/**/TEST-*.xml", recursive=True)):
    suite = ET.parse(path).getroot()
    t, f, e, s = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    total, failed, skipped = total + t, failed + f + e, skipped + s
    print(f"{suite.get('name')}: {t} tests, {f + e} failed, {s} skipped")
print(f"TOTAL: {total} tests, {failed} failed, {skipped} skipped")
if total == 0:
    sys.exit("No test results found")

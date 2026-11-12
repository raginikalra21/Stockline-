#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

report = Path("target/surefire-reports/TEST-in.stockline.PostgresIntegrationTest.xml")
if not report.exists():
    sys.exit("PostgreSQL integration test report is missing.")
suite = ET.parse(report).getroot()
skipped = int(suite.attrib.get("skipped", "0"))
failures = int(suite.attrib.get("failures", "0"))
errors = int(suite.attrib.get("errors", "0"))
if skipped or failures or errors:
    sys.exit(f"PostgreSQL integration tests must all run and pass (skipped={skipped}, failures={failures}, errors={errors}).")
print(f"PostgreSQL integration tests ran and passed: {suite.attrib.get('tests', '0')} tests.")

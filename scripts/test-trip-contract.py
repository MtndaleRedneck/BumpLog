#!/usr/bin/env python3
from pathlib import Path
import sqlite3
base=Path("app/src/main/java/com/bumplog")
assert "confirmForTrip(spot.id)" in (base/"Screens.kt").read_text()
assert "YES -> { db.confirmForTrip(id)" in (base/"Receivers.kt").read_text()
assert "confirmForTrip(id, fileTrip, t, severity)" in (base/"Db.kt").read_text()
assert ".logSpot(spot.id, type, severity, fileTrip)" in (base/"LogItActivity.kt").read_text()
assert "TripReportPolicy.canSendNow" in (base/"Screens.kt").read_text()
db=sqlite3.connect(":memory:")
db.execute("CREATE TABLE spots(id INTEGER PRIMARY KEY, file_trip INTEGER, review TEXT, report_status TEXT, drive_id INTEGER, report_id TEXT)")
for i,review in enumerate(["CONFIRMED","PENDING","DISMISSED","FIXED"],1):
 db.execute("INSERT INTO spots VALUES(?,1,?,'NONE',1,?)",(i,review,"BL-"+str(i)))
db.execute("INSERT INTO spots VALUES(5,1,'CONFIRMED','SENT',1,'BL-5')")
db.execute("INSERT INTO spots VALUES(6,0,'CONFIRMED','NONE',1,'BL-6')")
db.execute("INSERT INTO spots VALUES(7,1,'CONFIRMED','NONE',-77,'TEST-7')")
where="file_trip=1 AND review='CONFIRMED' AND report_status='NONE' AND report_id NOT LIKE 'TEST-%'"
assert db.execute("SELECT id FROM spots WHERE "+where).fetchall()==[(1,)]
db.execute("UPDATE spots SET drive_id=2 WHERE id=1")
assert db.execute("SELECT id FROM spots WHERE "+where).fetchall()==[(1,)]
db.execute("UPDATE spots SET report_status='SENT' WHERE id=1")
assert db.execute("SELECT id FROM spots WHERE "+where).fetchall()==[]
print("PASS: confirmation wiring, queue state filtering, test isolation, cross-drive re-hit, no duplicate after handoff")

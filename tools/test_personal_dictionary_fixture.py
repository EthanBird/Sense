from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from personal_dictionary_fixture import create_database, rows_for, validate_restored_database
import measure_personal_dictionary_android

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT/'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/PersistentUserLexicon.kt').read_text('utf-8')


class PersonalDictionaryFixtureTest(unittest.TestCase):
    def test_fixed_sizes_unique_identity_and_context_limits(self):
        for size in (1000,10000):
            rows=rows_for(size,2_000_000)
            self.assertEqual(size,len({(r['full_pinyin'],r['phrase']) for r in rows}))
            self.assertTrue(all(1 <= len(r['context_selections'].splitlines()) <= 8 for r in rows))
        with self.assertRaises(ValueError): rows_for(999,2_000_000)

    def test_production_schema_round_trip_and_durable_reuse_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'fixture.db'
            seed=create_database(path,1000,2_000_000,SOURCE)
            with self.assertRaises(ValueError): create_database(path,1000,2_000_000,SOURCE)
            with self.assertRaises(ValueError): validate_restored_database(path,seed)
            with closing(sqlite3.connect(path)) as db, db:
                self.assertEqual(4,db.execute('PRAGMA user_version').fetchone()[0])
                for row in seed['cases']:
                    code='quanli' if row['query']=='qvanli' else row['query']
                    db.execute('UPDATE user_phrase SET use_count=use_count+2 WHERE full_pinyin=? AND phrase=?',(code,row['expected']))
            self.assertEqual(7,validate_restored_database(path,seed)['sampledDurableUpdates'])
            with closing(sqlite3.connect(path)) as db, db: db.execute("UPDATE user_phrase SET context_selections='' WHERE phrase='权力'")
            with self.assertRaises(ValueError): validate_restored_database(path,seed)

    def test_runner_rejects_physical_devices_before_adb(self):
        with patch('sys.argv',['measure','fresh-output','--serial','user-phone']), patch('subprocess.run') as run:
            with self.assertRaises(ValueError): measure_personal_dictionary_android.main()
            run.assert_not_called()

    def test_runner_preserves_existing_evidence_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('sys.argv',['measure',directory]), patch('subprocess.run') as run:
                with self.assertRaises(ValueError): measure_personal_dictionary_android.main()
                run.assert_not_called()


if __name__ == '__main__': unittest.main()

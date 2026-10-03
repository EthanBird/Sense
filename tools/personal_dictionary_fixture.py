"""Deterministic, synthetic SQLite-v4 capacity data; never a natural-language corpus."""
import itertools
from contextlib import closing
from pathlib import Path
import sqlite3

PACKAGE = 'io.github.ethanbird.senseime.debug'
DATABASE = 'sense_user_lexicon.db'
CONTEXTS = ['人民', '获得', '今天', '明天', '公司', '项目', '家里', '学校']
UNITS = list(zip(
    'ba bo bu ca ce cu da de di du fa fo fu ga ge gu ha he hu ji ju ka ke ku la'.split(),
    '巴波布擦策粗达德迪杜发佛福嘎歌古哈何胡季居卡可库拉'))


def rows_for(size, now):
    if size not in (1000, 10000) or now <= 0:
        raise ValueError('Fixed capacity sizes and positive Android wall clock required')
    rows = []
    for index, units in enumerate(itertools.islice(itertools.product(UNITS, repeat=3), size-4)):
        code = ''.join(u[0] for u in units)
        phrase = ''.join(u[1] for u in units)
        timestamp = now - 5000 - index
        rows.append(dict(full_pinyin=code, phrase=phrase, initials=''.join(u[0][0] for u in units),
                         use_count=1, created_at_ms=timestamp, last_used_at_ms=timestamp,
                         aliases='qqq', positive_evidence=1.75, negative_evidence=0,
                         last_positive_evidence=1.75, last_negative_at_ms=0,
                         context_selections='\n'.join(f'{c}\t{timestamp}' for c in CONTEXTS)))
    for code, phrase, initials, aliases, count, contexts in [
        ('chengche','程彻','cc','',1,{'今天':now}),
        ('zhinengti','智能体','znt','',1,{'我的':now}),
        ('quanli','权力','ql','qvanli',1,{'人民':now,'获得':now-1}),
        ('quanli','权利','ql','qvanli',10,{'获得':now,'人民':now-1}),
    ]:
        rows.append(dict(full_pinyin=code, phrase=phrase, initials=initials, use_count=count,
                         created_at_ms=now, last_used_at_ms=now, aliases=aliases, positive_evidence=1.75,
                         negative_evidence=0, last_positive_evidence=1.75, last_negative_at_ms=0,
                         context_selections='\n'.join(f'{c}\t{t}' for c,t in contexts.items())))
    return rows


def create_database(path, size, now, source):
    """Use the production CREATE statements, without executing any source code."""
    path = Path(path)
    if path.exists():
        raise ValueError('Retain the previous fixture')
    start = source.index('CREATE TABLE $TABLE_PHRASE (')
    end = source.index('""".trimIndent()', start)
    table_sql = source[start:end].strip().removesuffix('"""').strip()
    table_sql = table_sql.replace('$TABLE_PHRASE', 'user_phrase')
    if 'WITHOUT ROWID' not in table_sql or '$' in table_sql or 'DATABASE_VERSION = 4' not in source:
        raise ValueError('Review the production schema before changing this fixture')
    rows = rows_for(size, now)
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path)) as db, db:
        db.execute('PRAGMA page_size=4096')
        db.execute('PRAGMA journal_mode=DELETE')
        db.execute(table_sql)
        db.execute('CREATE INDEX user_phrase_initials_rank ON user_phrase(initials, use_count DESC, last_used_at_ms DESC)')
        db.execute('PRAGMA user_version=4')
        fields = list(rows[0])
        db.executemany(f'INSERT INTO user_phrase ({",".join(fields)}) VALUES ({",".join("?" for _ in fields)})',
                       [tuple(row[f] for f in fields) for row in rows])
        assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
        assert db.execute('SELECT COUNT(*) FROM user_phrase').fetchone() == (size,)
    sample = [rows[i] for i in [0, (size-4)//2, size-5]]
    cases = [dict(query='chengche', expected='程彻', context=''),
             dict(query='zhinengti', expected='智能体', context=''),
             dict(query='qvanli', expected='权力', context='人民'),
             dict(query='quanli', expected='权利', context='获得')]
    cases += [dict(query=r['full_pinyin'], expected=r['phrase'], context='') for r in sample]
    return dict(schemaVersion=1, size=size, seedWallMillis=now,
                scope='Synthetic capacity, not natural text or accuracy benchmark', cases=cases,
                rows=rows)


def validate_restored_database(path, seed):
    """Defaults must update counts without losing context or replacing the 10k rows."""
    with closing(sqlite3.connect(path)) as db, db:
        db.row_factory = sqlite3.Row
        actual = {(r['full_pinyin'],r['phrase']):dict(r) for r in db.execute('SELECT * FROM user_phrase')}
        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('Android journal integrity failed')
    expected = {(r['full_pinyin'],r['phrase']):r for r in seed['rows']}
    if actual.keys() != expected.keys():
        raise ValueError('Personal rows added or lost during default reuse')
    sampled = {('quanli' if r['query']=='qvanli' else r['query'],r['expected']) for r in seed['cases']}
    for key, row in actual.items():
        before = expected[key]
        if row['context_selections'] != before['context_selections']:
            # Encoding canonicalizes order; compare maps rather than order of equivalent entries.
            parse = lambda value: dict(line.split('\t') for line in value.splitlines())
            if parse(row['context_selections']) != parse(before['context_selections']):
                raise ValueError('Default reuse changed explicit contexts')
        if key in sampled and row['use_count'] < before['use_count'] + 2:
            raise ValueError(f'Both process phases must durably learn default reuse: {key}')
        if key not in sampled and row != before:
            raise ValueError('Unrelated seeded personal row changed')
    return dict(rows=len(actual), contextsPreserved=True, untouchedRowsUnchanged=True,
                sampledDurableUpdates=len(sampled), integrity='ok')

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  token TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 0,
  period INTEGER NOT NULL DEFAULT 14,
  low REAL NOT NULL DEFAULT 30,
  high REAL NOT NULL DEFAULT 70,
  last_close INTEGER,
  zone TEXT,
  updated_at INTEGER NOT NULL
);
CREATE TABLE alerts (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  close_time INTEGER NOT NULL,
  zone TEXT NOT NULL,
  rsi REAL NOT NULL,
  price REAL NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  attempts INTEGER NOT NULL DEFAULT 0,
  lease_until INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX alerts_pending ON alerts(status, lease_until);
CREATE TABLE health (id INTEGER PRIMARY KEY CHECK (id = 1), checked_at INTEGER, candle_time INTEGER, error TEXT);

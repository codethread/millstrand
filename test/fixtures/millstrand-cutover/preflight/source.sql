CREATE TABLE strands (id INTEGER PRIMARY KEY, title TEXT NOT NULL);
CREATE TABLE attributes (strand_id INTEGER NOT NULL, key TEXT NOT NULL, value TEXT);
CREATE TABLE burn_history (id INTEGER PRIMARY KEY, strand_id INTEGER NOT NULL);
CREATE TABLE scheduler_history (id INTEGER PRIMARY KEY, event TEXT NOT NULL);
INSERT INTO strands VALUES (1, 'fixture strand'), (2, 'agent run');
INSERT INTO attributes VALUES
  (1, 'agent-run/cost-usd', '0.25'),
  (1, 'agent-run/tokens', '100'),
  (1, 'agent-run/tokens-total', '100');
INSERT INTO burn_history VALUES (1, 99);
INSERT INTO scheduler_history VALUES (1, 'cancelled');

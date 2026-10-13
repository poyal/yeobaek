-- Source trace is identified by manifest.json. Durations are inclusive nanoseconds.
SELECT name, COUNT(*) AS samples,
       AVG(dur) / 1000000.0 AS avg_ms,
       PERCENTILE(dur / 1000000.0, 95) AS p95_ms,
       MAX(dur) / 1000000.0 AS max_ms
FROM slice WHERE name = 'MeasureTextBlock' AND dur > 0 GROUP BY name;

SELECT t.name AS thread, s.name, COUNT(*) AS samples,
       SUM(s.dur) / 1000000.0 AS inclusive_ms,
       AVG(s.dur) / 1000000.0 AS avg_ms,
       MAX(s.dur) / 1000000.0 AS max_ms
FROM slice s JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t ON t.utid = tt.utid JOIN process p ON p.upid = t.upid
WHERE p.name = 'com.poyal.yeobaek.benchmark' AND s.dur > 0
GROUP BY t.name, s.name ORDER BY inclusive_ms DESC LIMIT 30;

use chrono::{DateTime, FixedOffset, Local};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime};

const LOG_RETENTION: Duration = Duration::from_secs(7 * 24 * 60 * 60);
const MAX_LOG_BYTES: u64 = 4 * 1024 * 1024;
const LOG_PREFIX: &str = "autopatcher-";
const LOG_SUFFIX: &str = ".log";
const LIMIT_MARKER: &str = "WARN daily log size limit reached; further entries are omitted\n";
static LOGGER: OnceLock<Mutex<Option<FileLogger>>> = OnceLock::new();

#[derive(Debug)]
struct FileLogger {
    root: PathBuf,
    day: String,
    path: PathBuf,
    file: File,
}

pub fn init(logs_root: &Path) -> io::Result<PathBuf> {
    let mut logger = LOGGER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| io::Error::other("logger mutex poisoned"))?;
    let now = Local::now().fixed_offset();
    if let Some(logger) = logger.as_mut() {
        if logger.root != logs_root {
            return Err(io::Error::other(
                "logger already initialized at a different directory",
            ));
        }
        logger.rotate(now)?;
        return Ok(logger.path.clone());
    }
    fs::create_dir_all(logs_root)?;
    cleanup_old_logs(logs_root, SystemTime::now())?;
    let opened = FileLogger::open(logs_root, now)?;
    let path = opened.path.clone();
    *logger = Some(opened);
    Ok(path)
}

pub fn info(message: impl AsRef<str>) {
    write("INFO", message.as_ref());
}
pub fn warn(message: impl AsRef<str>) {
    write("WARN", message.as_ref());
}
pub fn error(message: impl AsRef<str>) {
    write("ERROR", message.as_ref());
}
fn write(level: &str, message: &str) {
    let Some(logger) = LOGGER.get() else {
        return;
    };
    let Ok(mut logger) = logger.lock() else {
        return;
    };
    if let Some(logger) = logger.as_mut() {
        let _ = logger.write_entry(level, message, Local::now().fixed_offset());
    }
}
impl FileLogger {
    fn open(root: &Path, now: DateTime<FixedOffset>) -> io::Result<Self> {
        let day = now.format("%Y-%m-%d").to_string();
        let path = root.join(format!("{LOG_PREFIX}{day}{LOG_SUFFIX}"));
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .append(true)
            .open(&path)?;
        Ok(Self {
            root: root.into(),
            day,
            path,
            file,
        })
    }
    fn rotate(&mut self, now: DateTime<FixedOffset>) -> io::Result<()> {
        if now.format("%Y-%m-%d").to_string() != self.day {
            let next = Self::open(&self.root, now)?;
            *self = next;
            cleanup_old_logs(&self.root, SystemTime::now())?;
        }
        Ok(())
    }
    fn write_entry(
        &mut self,
        level: &str,
        message: &str,
        now: DateTime<FixedOffset>,
    ) -> io::Result<()> {
        self.rotate(now)?;
        // Share one daily file across GUI/TUI processes without interleaving lines.
        self.file.lock()?;
        let result = self.write_locked(level, message, now);
        let unlock = self.file.unlock();
        result.and(unlock)
    }
    fn write_locked(
        &mut self,
        level: &str,
        message: &str,
        now: DateTime<FixedOffset>,
    ) -> io::Result<()> {
        let size = self.file.metadata()?.len();
        if size >= MAX_LOG_BYTES {
            return Ok(());
        }
        // Persist the size-limit marker across restarts instead of adding a marker on every run.
        if size >= LIMIT_MARKER.len() as u64 {
            self.file
                .seek(SeekFrom::End(-(LIMIT_MARKER.len() as i64)))?;
            let mut tail = vec![0; LIMIT_MARKER.len()];
            self.file.read_exact(&mut tail)?;
            if tail == LIMIT_MARKER.as_bytes() {
                return Ok(());
            }
        }
        let line = format!(
            "[{}] [pid:{}] {level} {}\n",
            now.format("%Y-%m-%d %H:%M:%S%:z"),
            std::process::id(),
            sanitize(message)
        );
        if size.saturating_add(line.len() as u64) > MAX_LOG_BYTES {
            self.file.write_all(LIMIT_MARKER.as_bytes())?;
        } else {
            self.file.write_all(line.as_bytes())?;
        }
        self.file.flush()
    }
}
fn sanitize(message: &str) -> String {
    message.replace('\r', " ").replace('\n', " | ")
}
fn cleanup_old_logs(logs_root: &Path, now: SystemTime) -> io::Result<()> {
    for entry in fs::read_dir(logs_root)? {
        let entry = entry?;
        let path = entry.path();
        if !path.is_file() || !is_log_file(&path) {
            continue;
        }
        let Ok(modified) = entry.metadata().and_then(|m| m.modified()) else {
            continue;
        };
        if now
            .duration_since(modified)
            .is_ok_and(|age| age > LOG_RETENTION)
        {
            let _ = fs::remove_file(path);
        }
    }
    Ok(())
}
fn is_log_file(path: &Path) -> bool {
    path.file_name()
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.starts_with(LOG_PREFIX) && n.ends_with(LOG_SUFFIX))
}
#[cfg(test)]
mod tests {
    use super::*;
    fn at(time: &str) -> DateTime<FixedOffset> {
        DateTime::parse_from_rfc3339(time).unwrap()
    }
    #[test]
    fn multiple_runs_append_to_the_same_local_date() {
        let temp = tempfile::tempdir().unwrap();
        let now = at("2026-09-06T00:10:00+09:00");
        let mut first = FileLogger::open(temp.path(), now).unwrap();
        first.write_entry("INFO", "first run", now).unwrap();
        drop(first);
        let mut second = FileLogger::open(temp.path(), now).unwrap();
        second.write_entry("INFO", "second run", now).unwrap();
        assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 1);
        let text = fs::read_to_string(temp.path().join("autopatcher-2026-09-06.log")).unwrap();
        assert!(text.contains("first run") && text.contains("second run"));
        assert!(text.contains("2026-09-06 00:10:00+09:00"));
    }
    #[test]
    fn midnight_rolls_over_and_returning_to_a_date_appends() {
        let temp = tempfile::tempdir().unwrap();
        let before = at("2026-09-06T23:59:59+09:00");
        let after = at("2026-09-07T00:00:01+09:00");
        let mut logger = FileLogger::open(temp.path(), before).unwrap();
        logger.write_entry("INFO", "before", before).unwrap();
        logger.write_entry("INFO", "after", after).unwrap();
        logger
            .write_entry("INFO", "clock adjusted", before)
            .unwrap();
        assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 2);
        let first = fs::read_to_string(temp.path().join("autopatcher-2026-09-06.log")).unwrap();
        assert!(
            first.contains("before")
                && first.contains("clock adjusted")
                && !first.contains("after")
        );
    }
    #[test]
    fn daily_size_limit_persists_across_reopen() {
        let temp = tempfile::tempdir().unwrap();
        let now = at("2026-09-06T12:00:00+09:00");
        File::create(temp.path().join("autopatcher-2026-09-06.log"))
            .unwrap()
            .set_len(MAX_LOG_BYTES - 4)
            .unwrap();
        let mut logger = FileLogger::open(temp.path(), now).unwrap();
        logger.write_entry("INFO", "over limit", now).unwrap();
        let length = logger.file.metadata().unwrap().len();
        drop(logger);
        let mut logger = FileLogger::open(temp.path(), now).unwrap();
        logger.write_entry("INFO", "also over limit", now).unwrap();
        assert_eq!(logger.file.metadata().unwrap().len(), length);
    }
    #[test]
    fn independent_writers_keep_complete_lines_in_one_file() {
        let temp = tempfile::tempdir().unwrap();
        let mut workers = Vec::new();
        for writer in 0..2 {
            let root = temp.path().to_path_buf();
            workers.push(std::thread::spawn(move || {
                let now = at("2026-09-06T12:00:00+09:00");
                let mut logger = FileLogger::open(&root, now).unwrap();
                for entry in 0..32 {
                    logger
                        .write_entry("INFO", &format!("writer={writer} entry={entry}"), now)
                        .unwrap();
                }
            }));
        }
        for worker in workers {
            worker.join().unwrap();
        }
        let text = fs::read_to_string(temp.path().join("autopatcher-2026-09-06.log")).unwrap();
        assert_eq!(text.lines().count(), 64);
        for writer in 0..2 {
            for entry in 0..32 {
                assert!(text.contains(&format!("writer={writer} entry={entry}\n")));
            }
        }
    }

    #[test]
    fn cleanup_removes_only_old_autopatcher_logs() {
        let temp = tempfile::tempdir().unwrap();
        let old = temp.path().join("autopatcher-old.log");
        let keep = temp.path().join("notes.log");
        fs::write(&old, b"old").unwrap();
        fs::write(&keep, b"keep").unwrap();
        cleanup_old_logs(
            temp.path(),
            SystemTime::now() + LOG_RETENTION + Duration::from_secs(1),
        )
        .unwrap();
        assert!(!old.exists());
        assert!(keep.exists());
    }
}

//! Fail-closed CGL Linux/ELF compatibility pilot; NOT realtime certification.
//! Std-only so the gate can be compiled with rustc on a hosted Linux runner.
#![forbid(unsafe_op_in_unsafe_fn)]

use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;

const MAX_PH: usize = 128;
const MAX_INTERP: u64 = 4096;
const O_NONBLOCK: i32 = 0o4000;
const O_NOFOLLOW: i32 = 0o400000;
const O_CLOEXEC: i32 = 0o2000000;

#[derive(Debug, PartialEq, Eq)]
struct Elf {
    machine: u16,
    kind: &'static str,
    interpreter: Option<String>,
}

fn le16(s: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([s[at], s[at + 1]])
}
fn le32(s: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(s[at..at + 4].try_into().expect("bounded ELF header"))
}
fn le64(s: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(s[at..at + 8].try_into().expect("bounded ELF header"))
}
fn err(message: &str) -> String {
    message.to_string()
}
fn open_no_follow(path: &Path) -> Result<File, String> {
    OpenOptions::new().read(true)
        .custom_flags(O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC)
        .open(path).map_err(|e| format!("cannot open file: {e}"))
}
fn rt_flag(path: &Path) -> Option<bool> {
    let f = open_no_follow(path).ok()?;
    if !f.metadata().ok()?.is_file() { return None; }
    let mut raw = Vec::new();
    f.take(8).read_to_end(&mut raw).ok()?;
    match raw.as_slice() {
        b"1" | b"1\n" => Some(true),
        b"0" | b"0\n" => Some(false),
        _ => None,
    }
}
fn inspect_elf(path: &Path) -> Result<Elf, String> {
    let mut f = open_no_follow(path)?;
    let meta = f.metadata().map_err(|e| e.to_string())?;
    if !meta.is_file() { return Err(err("ELF target is not a regular file")); }
    if meta.permissions().mode() & 0o111 == 0 {
        return Err(err("ELF has no executable permission bits"));
    }
    let file_len = meta.len();
    let mut h = [0u8; 64];
    f.read_exact(&mut h).map_err(|_| err("not a complete ELF64 header"))?;
    if &h[0..4] != b"\x7fELF" { return Err(err("not an ELF executable")); }
    if h[4..7] != [2, 1, 1] || !matches!(h[7], 0 | 3) {
        return Err(err("unsupported ELF64 class, endian, version or OS ABI"));
    }
    let kind = le16(&h, 16);
    let machine = le16(&h, 18);
    if !matches!(kind, 2 | 3) || le32(&h, 20) != 1 {
        return Err(err("not a supported executable or PIE"));
    }
    if !matches!(machine, 62 | 183) {
        return Err(err("unsupported ELF machine architecture"));
    }
    let entry = le64(&h, 24);
    let phoff = le64(&h, 32);
    let phnum = le16(&h, 56) as usize;
    if le16(&h, 52) != 64 || le16(&h, 54) != 56 || phnum == 0 || phnum > MAX_PH {
        return Err(err("invalid ELF header/program-header sizes"));
    }
    let ph_size = (phnum as u64) * 56;
    if phoff < 64 || phoff.checked_add(ph_size).map_or(true, |end| end > file_len) {
        return Err(err("ELF program-header table exceeds file"));
    }
    f.seek(SeekFrom::Start(phoff)).map_err(|e| e.to_string())?;
    let mut table = vec![0u8; ph_size as usize];
    f.read_exact(&mut table).map_err(|_| err("truncated ELF program-header table"))?;
    let mut has_load = false;
    let mut executable_load = false;
    let mut entry_in_executable_load = false;
    let mut interpreter: Option<String> = None;
    for ph in table.chunks_exact(56) {
        let kind_ph = le32(ph, 0);
        let flags = le32(ph, 4);
        let off = le64(ph, 8);
        let vaddr = le64(ph, 16);
        let filesz = le64(ph, 32);
        let memsz = le64(ph, 40);
        let align = le64(ph, 48);
        if kind_ph == 1 {
            has_load = true;
            if flags & 3 == 3 { return Err(err("ELF PT_LOAD has writable executable mapping")); }
            if off.checked_add(filesz).map_or(true, |end| end > file_len) {
                return Err(err("ELF PT_LOAD extends beyond file"));
            }
            if memsz < filesz { return Err(err("ELF PT_LOAD file size exceeds memory size")); }
            if memsz != 0 && vaddr.checked_add(memsz).map_or(true, |end| end == 0) {
                return Err(err("ELF PT_LOAD virtual address range overflows ELF64"));
            }
            if align > 1 && (!align.is_power_of_two() || vaddr % align != off % align) {
                return Err(err("ELF PT_LOAD alignment/offset mismatch"));
            }
            if flags & 1 != 0 {
                if filesz == 0 { return Err(err("ELF executable PT_LOAD has no file-backed bytes")); }
                executable_load = true;
                let end = vaddr.checked_add(filesz)
                    .ok_or_else(|| err("ELF executable PT_LOAD virtual range overflows"))?;
                if (vaddr..end).contains(&entry) { entry_in_executable_load = true; }
            }
        } else if kind_ph == 0x6474_e551 && flags & 1 != 0 {
            return Err(err("ELF requests executable PT_GNU_STACK"));
        } else if kind_ph == 3 {
            if interpreter.is_some() { return Err(err("ELF has multiple PT_INTERP segments")); }
            if !(2..=MAX_INTERP).contains(&filesz) ||
               off.checked_add(filesz).map_or(true, |end| end > file_len) {
                return Err(err("invalid ELF PT_INTERP bounds"));
            }
            let mut raw = vec![0u8; filesz as usize];
            f.seek(SeekFrom::Start(off)).map_err(|e| e.to_string())?;
            f.read_exact(&mut raw).map_err(|_| err("ELF PT_INTERP truncated"))?;
            if raw.last() != Some(&0) || raw[..raw.len() - 1].contains(&0) {
                return Err(err("ELF interpreter is not a single NUL-terminated path"));
            }
            let p = std::str::from_utf8(&raw[..raw.len() - 1])
                .map_err(|_| err("invalid ELF interpreter pathname"))?;
            if !Path::new(p).is_absolute() {
                return Err(err("ELF interpreter must be an absolute path"));
            }
            let loader = fs::metadata(p).map_err(|_| err("ELF interpreter not present"))?;
            if !loader.is_file() || loader.permissions().mode() & 0o111 == 0 {
                return Err(err("ELF interpreter is not an executable regular file"));
            }
            interpreter = Some(p.to_owned());
        }
    }
    if !has_load { return Err(err("ELF lacks loadable segments")); }
    if !executable_load { return Err(err("ELF lacks an executable PT_LOAD segment")); }
    if !entry_in_executable_load { return Err(err("ELF entry point is outside file-backed executable PT_LOAD")); }
    if kind == 3 && interpreter.is_none() {
        return Err(err("ET_DYN without interpreter is not admitted by pilot"));
    }
    Ok(Elf { machine, kind: if kind == 2 { "ET_EXEC" } else { "ET_DYN" }, interpreter })
}
#[repr(C)]
struct Timespec { sec: i64, nano: i64 }
extern "C" {
    fn sched_getaffinity(pid: i32, size: usize, mask: *mut u8) -> i32;
    fn sched_getscheduler(pid: i32) -> i32;
    fn clock_getres(clock_id: i32, result: *mut Timespec) -> i32;
}
fn cpu_count() -> Option<u32> {
    let mut cpus = [0u8; 8192];
    // SAFETY: allocated byte array is valid and the supplied byte length is exact.
    if unsafe { sched_getaffinity(0, cpus.len(), cpus.as_mut_ptr()) } != 0 { return None; }
    let count = cpus.iter().map(|byte| byte.count_ones()).sum();
    if count == 0 { None } else { Some(count) }
}
fn scheduler() -> Option<i32> {
    // SAFETY: sched_getscheduler has no caller-owned pointer arguments.
    let n = unsafe { sched_getscheduler(0) };
    (n >= 0).then_some(n)
}
fn monotonic_resolution() -> Option<f64> {
    let mut time = Timespec { sec: 0, nano: 0 };
    // SAFETY: clock_getres writes exactly one valid timespec.
    if unsafe { clock_getres(1, &mut time) } != 0 { return None; }
    if time.sec < 0 || !(0..1_000_000_000).contains(&time.nano) { return None; }
    Some(time.sec as f64 + time.nano as f64 / 1e9)
}
fn json_escape(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
fn run(binary: Option<&Path>, require_rt: bool) -> (bool, String) {
    let mut errors: Vec<String> = Vec::new();
    let arch = std::env::consts::ARCH;
    if !cfg!(target_os = "linux") { errors.push(err("host is not Linux")); }
    let machine = match arch {
        "x86_64" => Some(62), "aarch64" => Some(183),
        _ => { errors.push(err("architecture is outside x86_64/aarch64 pilot")); None }
    };
    let cpus = cpu_count();
    if cpus.is_none() { errors.push(err("cannot inspect nonempty effective CPU affinity")); }
    let policy = scheduler();
    if policy.is_none() { errors.push(err("cannot inspect effective scheduler")); }
    let resolution = monotonic_resolution();
    if resolution.is_none() { errors.push(err("monotonic clock unavailable")); }
    let rt = rt_flag(Path::new("/sys/kernel/realtime"));
    if require_rt && rt != Some(true) {
        errors.push(err("PREEMPT_RT cannot be verified from /sys/kernel/realtime"));
    }
    let elf = match binary.map(inspect_elf) {
        None => None,
        Some(Ok(elf)) => {
            if machine.is_some_and(|expected| expected != elf.machine) {
                errors.push(err("ELF machine does not match host architecture"));
            }
            Some(elf)
        }
        Some(Err(why)) => { errors.push(format!("ELF inspection failed: {why}")); None }
    };
    let bool_or_null = |v: Option<bool>| -> String {
        match v { Some(true) => "true", Some(false) => "false", None => "null" }.to_string()
    };
    let number_or_null = |v: Option<i32>| -> String {
        v.map_or_else(|| "null".into(), |n| n.to_string())
    };
    let kernel = fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
    let elf_json = elf.as_ref().map_or_else(|| "null".to_string(), |e| {
        let interp = e.interpreter.as_ref().map_or_else(|| "null".to_string(), |p| json_escape(p));
        format!(r#"{{"machine":{},"type":{},"interpreter":{}}}"#, e.machine, json_escape(e.kind), interp)
    });
    let machine_json = elf.as_ref().map_or_else(|| "null".to_string(), |e| e.machine.to_string());
    let err_json = errors.iter().map(|e| json_escape(e)).collect::<Vec<_>>().join(",");
    let json = format!(
        r#"{{"schema":"oreslang.cgl.compat.v1","compatibility":"{}","carrier_grade_qualified":false,"hard_realtime_qualified":false,"host":{{"linux":{},"architecture":{},"kernel":{},"affinity_cpu_count":{},"scheduler_policy":{},"monotonic_resolution_seconds":{},"preempt_rt_sysfs":{}}},"elf":{},"elf_machine":{},"errors":[{}]}}"#,
        if errors.is_empty() { "pass" } else { "fail" }, cfg!(target_os = "linux"),
        json_escape(arch), json_escape(kernel.trim()),
        cpus.unwrap_or(0), number_or_null(policy),
        resolution.map_or_else(|| "null".to_string(), |n| n.to_string()),
        bool_or_null(rt), elf_json, machine_json, err_json
    );
    (errors.is_empty(), json)
}
fn main() {
    let mut args = std::env::args_os().skip(1);
    let mut binary = None;
    let mut require_rt = false;
    while let Some(arg) = args.next() {
        if arg == "--binary" {
            match args.next() {
                Some(value) => binary = Some(std::path::PathBuf::from(value)),
                None => { eprintln!("missing --binary value"); std::process::exit(2); }
            }
        } else if arg == "--require-preempt-rt" {
            require_rt = true;
        } else {
            eprintln!("unknown CLI argument: {:?}", arg);
            std::process::exit(2);
        }
    }
    let (ok, report) = run(binary.as_deref(), require_rt);
    println!("{report}");
    if !ok { std::process::exit(2); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture { path: std::path::PathBuf }
    impl Fixture {
        fn new(bytes: &[u8]) -> Self {
            let seq = NEXT.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!("ores-cgl-{}-{}-{}", std::process::id(), seq, std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
            let mut f = File::create(&path).unwrap();
            f.write_all(bytes).unwrap();
            fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
            Self { path }
        }
        fn inspect(&self) -> Result<Elf, String> { inspect_elf(&self.path) }
    }
    impl Drop for Fixture { fn drop(&mut self) { let _ = fs::remove_file(&self.path); } }
    fn put16(v: &mut [u8], p: usize, n: u16) { v[p..p+2].copy_from_slice(&n.to_le_bytes()); }
    fn put32(v: &mut [u8], p: usize, n: u32) { v[p..p+4].copy_from_slice(&n.to_le_bytes()); }
    fn put64(v: &mut [u8], p: usize, n: u64) { v[p..p+8].copy_from_slice(&n.to_le_bytes()); }
    fn elf(machine: u16, flags: u32, stack: Option<u32>, kind: u16, interp: Option<&[u8]>) -> Vec<u8> {
        let n = 1 + usize::from(stack.is_some()) + usize::from(interp.is_some());
        let mut v = vec![0u8; 64 + n * 56 + interp.map_or(0, |i| i.len())];
        v[..8].copy_from_slice(b"\x7fELF\x02\x01\x01\x00");
        put16(&mut v, 16, kind);
        put16(&mut v, 18, machine);
        put32(&mut v, 20, 1);
        put64(&mut v, 32, 64);
        put16(&mut v, 52, 64);
        put16(&mut v, 54, 56);
        put16(&mut v, 56, n as u16);
        put32(&mut v, 64, 1);
        put32(&mut v, 68, flags);
        // Populate a real file-backed RX load, not just an executable flag.
        let bytes = v.len() as u64;
        put64(&mut v, 64 + 32, bytes);
        put64(&mut v, 64 + 40, bytes);
        put64(&mut v, 112, 4096);
        let mut p = 120;
        if let Some(s) = stack {
            put32(&mut v, p, 0x6474_e551);
            put32(&mut v, p + 4, s);
            p += 56;
        }
        if let Some(i) = interp {
            put32(&mut v, p, 3);
            put64(&mut v, p+8, (64 + n*56) as u64);
            put64(&mut v, p+32, i.len() as u64);
            put64(&mut v, p+40, i.len() as u64);
            v[64 + n*56..].copy_from_slice(i);
        }
        v
    }
    #[test] fn admits_supported_machines() {
        for m in [62, 183] { assert_eq!(Fixture::new(&elf(m,5,None,2,None)).inspect().unwrap().machine,m); }
    }
    #[test] fn denies_foreign_machine() {
        for m in [0,3,40,65535] { assert!(Fixture::new(&elf(m,5,None,2,None)).inspect().unwrap_err().contains("machine")); }
    }
    #[test] fn denies_writable_executable_load() {
        for flags in [3,7] { assert!(Fixture::new(&elf(62,flags,None,2,None)).inspect().unwrap_err().contains("writable executable")); }
    }
    #[test] fn denies_executable_stack() {
        for flags in [1,5,7] { assert!(Fixture::new(&elf(62,5,Some(flags),2,None)).inspect().unwrap_err().contains("executable PT_GNU_STACK")); }
        assert!(Fixture::new(&elf(62,5,Some(6),2,None)).inspect().is_ok());
    }
    #[test] fn rejects_empty_executable_and_unmapped_entry() {
        let mut empty = elf(62, 5, None, 2, None);
        put64(&mut empty, 64 + 32, 0);
        put64(&mut empty, 64 + 40, 0);
        assert!(Fixture::new(&empty).inspect().unwrap_err().contains("no file-backed bytes"));
        let mut unmapped = elf(62, 5, None, 2, None);
        put64(&mut unmapped, 24, 0x8000);
        assert!(Fixture::new(&unmapped).inspect().unwrap_err().contains("entry point"));
        let mut boundary = elf(62, 5, None, 2, None);
        let last = boundary.len() as u64;
        put64(&mut boundary, 24, last);
        assert!(Fixture::new(&boundary).inspect().unwrap_err().contains("entry point"));
        let mut valid = elf(62, 5, None, 2, None);
        put64(&mut valid, 24, last - 1);
        assert!(Fixture::new(&valid).inspect().is_ok());
    }
    #[test] fn denies_data_only_load() {
        for flags in [0,2,4,6] { assert!(Fixture::new(&elf(62,flags,None,2,None)).inspect().unwrap_err().contains("executable PT_LOAD")); }
    }
    #[test] fn rejects_invalid_headers() {
        let base = elf(62,5,None,2,None);
        for variant in 0..8 {
            let mut v = base.clone();
            match variant {
                0 => v[0]=0,
                1 => v[4]=1,
                2 => v[5]=2,
                3 => v[6]=2,
                4 => v[7]=9,
                5 => put16(&mut v,16,1),
                6 => put16(&mut v,54,0),
                _ => put16(&mut v,56,129),
            }
            assert!(Fixture::new(&v).inspect().is_err(), "variant {variant}");
        }
    }
    #[test] fn denies_header_overflow_and_load_bounds() {
        let mut v=elf(62,5,None,2,None);
        put64(&mut v,32,100_000);
        assert!(Fixture::new(&v).inspect().is_err());
        let mut v=elf(62,5,None,2,None);
        put64(&mut v,64+32,100_000);
        assert!(Fixture::new(&v).inspect().is_err());
    }
    #[test] fn denies_memsz_smaller_than_filesz() {
        let mut v=elf(62,5,None,2,None);
        put64(&mut v,64+40,1);
        assert!(Fixture::new(&v).inspect().is_err());
    }
    #[test] fn denies_load_address_wraparound() {
        let mut v=elf(62,5,None,2,None);
        put64(&mut v,64+16,u64::MAX);
        assert!(Fixture::new(&v).inspect().unwrap_err().contains("overflows"));
    }
    #[test] fn denies_bad_load_alignment() {
        let mut v=elf(62,5,None,2,None);
        put64(&mut v,64+48,3);
        assert!(Fixture::new(&v).inspect().is_err());
    }
    #[test] fn denies_pie_without_loader_and_relative_loader() {
        assert!(Fixture::new(&elf(62,5,None,3,None)).inspect().is_err());
        assert!(Fixture::new(&elf(62,5,None,3,Some(b"relative/ld.so\0"))).inspect().is_err());
    }
    #[test] fn denies_symlinked_binary() {
        let file=Fixture::new(&elf(62,5,None,2,None));
        let path=file.path.with_extension("link");
        std::os::unix::fs::symlink(&file.path,&path).unwrap();
        assert!(inspect_elf(&path).is_err());
        fs::remove_file(path).unwrap();
    }
    #[test] fn verifies_rt_tristate_and_malformed_values() {
        for (raw,expected) in [
            (b"1".as_slice(),Some(true)),(b"1\n",Some(true)),(b"0",Some(false)),
            (b"0\n",Some(false)),(b" 1",None),(b"garbage",None)
        ] {
            let file=Fixture::new(raw);
            assert_eq!(rt_flag(&file.path),expected);
        }
    }
    #[test] fn denies_non_executable_file_and_missing_path() {
        let file = Fixture::new(&elf(62, 5, None, 2, None));
        fs::set_permissions(&file.path, fs::Permissions::from_mode(0o644)).unwrap();
        assert!(file.inspect().unwrap_err().contains("executable permission"));
        assert!(inspect_elf(&file.path.with_extension("missing")).is_err());
    }
    #[test] fn rejects_malformed_interpreter_segments() {
        let mut invalid = elf(62, 5, None, 3, Some(b"/bin/sh\0"));
        // The fixture has one PT_LOAD followed by PT_INTERP.
        let offset = 64 + 56;
        put64(&mut invalid, offset + 32, u64::MAX);
        assert!(Fixture::new(&invalid).inspect().is_err());
        let mut invalid = elf(62, 5, None, 3, Some(b"/bin/sh\0"));
        *invalid.last_mut().unwrap() = 1; // remove required interpreter terminator
        assert!(Fixture::new(&invalid).inspect().is_err());
    }
    #[test] fn rejects_unsafe_rt_flag_encodings() {
        for raw in [b"1\n\n".as_slice(), b"1\0", b"true", b"1 ", b"0 ", b"\n"] {
            let file = Fixture::new(raw);
            assert_eq!(rt_flag(&file.path), None);
        }
        let file = Fixture::new(b"1\n");
        let link = file.path.with_extension("link");
        std::os::unix::fs::symlink(&file.path, &link).unwrap();
        assert_eq!(rt_flag(&link), None);
        fs::remove_file(link).unwrap();
    }
    #[test] fn reports_no_certification() {
        let (_,json)=run(None,false);
        assert!(json.contains(r#""carrier_grade_qualified":false"#));
        assert!(json.contains(r#""hard_realtime_qualified":false"#));
    }
}

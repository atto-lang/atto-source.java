use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::time::{SystemTime,UNIX_EPOCH};

fn root()->PathBuf{
 let t=SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
 let p=std::env::temp_dir().join(format!("ores-verify-cli-{}-{t}",std::process::id()));
 fs::create_dir(&p).unwrap();p
}
fn invoke(args:&[&str])->std::process::Output{
 Command::new(env!("CARGO_BIN_EXE_oreslang-verify")).args(args).output().unwrap()
}
#[test]fn malformed_source_is_not_reported_clean(){
 let p=root();let file=p.join("bad.ores");fs::write(&file,"fnc f() { /* unclosed").unwrap();
 let out=invoke(&["lint",file.to_str().unwrap()]);
 assert!(!out.status.success());
 assert!(String::from_utf8_lossy(&out.stderr).contains("unterminated"));
 fs::remove_dir_all(p).unwrap();
}
#[test]fn large_source_fails_before_analysis(){
 let p=root();let file=p.join("oversize.ores");
 let f=fs::File::create(&file).unwrap();f.set_len(4*1024*1024+1).unwrap();
 let out=invoke(&["lint",file.to_str().unwrap()]);
 assert!(!out.status.success());
 assert!(String::from_utf8_lossy(&out.stderr).contains("4 MiB"));
 fs::remove_dir_all(p).unwrap();
}
#[test]fn valid_json_output_and_strict_warning(){
 let p=root();let file=p.join("warnings.ores");
 fs::write(&file,"fnc f(){ loop { defer close(); } }").unwrap();
 let out=invoke(&["lint","--format","json",file.to_str().unwrap()]);
 assert!(out.status.success(),"{:?}",String::from_utf8_lossy(&out.stderr));
 let value:serde_json::Value=serde_json::from_slice(&out.stdout).unwrap();
 assert_eq!(value[0]["code"],"ORES101");
 let strict=invoke(&["lint","--strict",file.to_str().unwrap()]);
 assert!(!strict.status.success());
 fs::remove_dir_all(p).unwrap();
}
#[cfg(unix)]
#[test]fn symlink_file_and_directory_scans_are_rejected(){
 use std::os::unix::fs::symlink;
 let p=root();let file=p.join("ok.ores");
 fs::write(&file,"fnc f(){ return; }").unwrap();
 let link=p.join("link.ores");symlink(&file,&link).unwrap();
 let out=invoke(&["lint",link.to_str().unwrap()]);
 assert!(!out.status.success());
 assert!(String::from_utf8_lossy(&out.stderr).contains("symlink"));
 let nested=p.join("nested");fs::create_dir(&nested).unwrap();
 symlink(&p,nested.join("cycle")).unwrap();
 let out=invoke(&["lint",p.to_str().unwrap()]);
 assert!(!out.status.success());
 assert!(String::from_utf8_lossy(&out.stderr).contains("symlink"));
 fs::remove_dir_all(p).unwrap();
}
#[test]fn bounded_model_cli_rejects_invalid_and_unexplored(){
 let zero=invoke(&["model","--workers","0"]);
 assert!(!zero.status.success());
 let cap=invoke(&["model","--max-states","1"]);
 assert!(!cap.status.success());
 assert!(String::from_utf8_lossy(&cap.stderr).contains("NOT exhaustive"));
}

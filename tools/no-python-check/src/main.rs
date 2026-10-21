use std::{env, fs, path::Path, process::{self, Command}};

fn forbidden_path(path: &str) -> bool {
    let p = path.to_ascii_lowercase();
    p.ends_with(".py") || p.ends_with(".pyi") || p.ends_with(".ipynb")
        || p.ends_with("/requirements.txt") || p == "requirements.txt"
        || p.ends_with("/pipfile") || p == "pipfile"
        || p.ends_with("/poetry.lock") || p == "poetry.lock"
        || p.ends_with("/pyproject.toml") || p == "pyproject.toml"
        || p.ends_with("/uv.lock") || p == "uv.lock"
        || p.ends_with("/setup.py") || p == "setup.py"
}

fn forbidden_workflow_line(line: &str) -> bool {
    let l = line.trim().to_ascii_lowercase();
    if l.starts_with('#') { return false; }
    ["actions/setup-python@", "python -m ", "python3 -m ", "python -c ", "python3 -c ",
     "python ", "python3 ", "pip install", "pip3 install", "uv run ",
     "pytest ", "poetry run "].iter().any(|s| l.contains(s))
}

fn violation(path: &str, body: &str) -> Vec<String> {
    let mut messages = Vec::new();
    if forbidden_path(path) { messages.push("Python file or dependency manifest".to_string()); }
    let p = path.to_ascii_lowercase();
    let workflow = (p.starts_with(".github/workflows/") && (p.ends_with(".yaml") || p.ends_with(".yml")))
        || p == "makefile" || p.ends_with("/makefile") || p.ends_with(".sh");
    if workflow {
        for (index, line) in body.lines().enumerate() {
            if forbidden_workflow_line(line) {
                messages.push(format!("Python invocation at line {}", index + 1));
            }
        }
    }
    messages
}

fn main() {
    let root = env::args().nth(1).unwrap_or_else(|| ".".to_string());
    let output = Command::new("git").args(["-C", &root, "ls-files", "-z"]).output()
        .expect("git must be installed");
    if !output.status.success() {
        eprintln!("git ls-files failed"); process::exit(2);
    }
    let mut found = 0;
    for raw in output.stdout.split(|byte| *byte == 0).filter(|v| !v.is_empty()) {
        let name = String::from_utf8_lossy(raw);
        let file = Path::new(&root).join(name.as_ref());
        if !file.is_file() { continue; }
        let body = fs::read_to_string(&file).unwrap_or_default();
        for problem in violation(&name, &body) {
            eprintln!("{}: {}", name, problem);
            found += 1;
        }
    }
    if found > 0 { eprintln!("{found} Python policy violation(s)"); process::exit(1); }
    println!("Rust-only tooling policy check passed");
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn blocks_python_assets() {
        assert!(forbidden_path("tool/doit.py"));
        assert!(forbidden_path("pyproject.toml"));
        assert!(forbidden_path("tests/a.ipynb"));
        assert!(!forbidden_path("README.md"));
        assert!(!forbidden_path("src/main.rs"));
    }
    #[test]
    fn blocks_python_ci_invocations() {
        assert!(forbidden_workflow_line("- uses: actions/setup-python@v5"));
        assert!(forbidden_workflow_line("- run: python3 -m pytest"));
        assert!(!forbidden_workflow_line("- run: cargo test"));
    }
}

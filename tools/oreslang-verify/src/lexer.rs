//! Lexer for advisory lint. Does not provide a compiler-grade AST.
#[derive(Clone,Debug)]
pub struct Token{pub value:String,pub line:usize,pub col:usize}
fn step(cs:&[char],i:&mut usize,line:&mut usize,col:&mut usize){
 if *i>=cs.len(){return}
 if cs[*i]=='\n'{*line+=1;*col=1}else{*col+=1}
 *i+=1;
}
pub fn tokens(src:&str)->Vec<Token>{
 let cs:Vec<char>=src.chars().collect();
 let(mut i,mut line,mut col)=(0,1,1);
 let mut result=vec![];
 while i<cs.len(){
  let c=cs[i];let next=cs.get(i+1).copied();
  if c.is_whitespace(){step(&cs,&mut i,&mut line,&mut col);continue}
  if c=='/'&&next==Some('/'){
   while i<cs.len()&&cs[i]!='\n'{step(&cs,&mut i,&mut line,&mut col)}
   continue
  }
  if c=='/'&&next==Some('*'){
   let mut depth=1usize;
   step(&cs,&mut i,&mut line,&mut col);step(&cs,&mut i,&mut line,&mut col);
   while i<cs.len()&&depth>0{
    if cs[i]=='/'&&cs.get(i+1)==Some(&'*'){
     depth+=1;step(&cs,&mut i,&mut line,&mut col);step(&cs,&mut i,&mut line,&mut col);
    }else if cs[i]=='*'&&cs.get(i+1)==Some(&'/'){
     depth-=1;step(&cs,&mut i,&mut line,&mut col);step(&cs,&mut i,&mut line,&mut col);
    }else{step(&cs,&mut i,&mut line,&mut col)}
   }
   continue
  }
  if c=='"'||c=='\''||c=='`'{
   let quote=c;
   step(&cs,&mut i,&mut line,&mut col);
   while i<cs.len(){
    if cs[i]=='\\'{
     step(&cs,&mut i,&mut line,&mut col);
     step(&cs,&mut i,&mut line,&mut col);
    }else if cs[i]==quote{
     step(&cs,&mut i,&mut line,&mut col);break
    }else{step(&cs,&mut i,&mut line,&mut col)}
   }
   continue
  }
  let(l,k)=(line,col);
  if c.is_alphabetic()||c=='_'{
   let start=i;
   while i<cs.len()&&(cs[i].is_alphanumeric()||cs[i]=='_'){
    step(&cs,&mut i,&mut line,&mut col)
   }
   result.push(Token{value:cs[start..i].iter().collect(),line:l,col:k});
  }else{
   result.push(Token{value:c.to_string(),line:l,col:k});
   step(&cs,&mut i,&mut line,&mut col);
  }
 }
 result
}
/// Reject malformed source instead of producing a misleading clean lint result.
/// This is lexical structural validation, not the Oreslang parser.
pub fn tokens_checked(src: &str) -> Result<Vec<Token>, String> {
    let cs: Vec<char> = src.chars().collect();
    let mut i = 0usize;
    let mut line = 1usize;
    let mut col = 1usize;
    let mut delimiters: Vec<(char, usize, usize)> = Vec::new();
    while i < cs.len() {
        let c = cs[i];
        let next = cs.get(i + 1).copied();
        if c == '/' && next == Some('/') {
            while i < cs.len() && cs[i] != '\n' {
                step(&cs, &mut i, &mut line, &mut col);
            }
            continue;
        }
        if c == '/' && next == Some('*') {
            let start = (line, col);
            let mut depth = 1usize;
            step(&cs, &mut i, &mut line, &mut col);
            step(&cs, &mut i, &mut line, &mut col);
            while i < cs.len() && depth != 0 {
                if cs[i] == '/' && cs.get(i + 1) == Some(&'*') {
                    depth += 1;
                    step(&cs, &mut i, &mut line, &mut col);
                    step(&cs, &mut i, &mut line, &mut col);
                } else if cs[i] == '*' && cs.get(i + 1) == Some(&'/') {
                    depth -= 1;
                    step(&cs, &mut i, &mut line, &mut col);
                    step(&cs, &mut i, &mut line, &mut col);
                } else {
                    step(&cs, &mut i, &mut line, &mut col);
                }
            }
            if depth != 0 {
                return Err(format!("unterminated block comment at {}:{}", start.0, start.1));
            }
            continue;
        }
        if c == '"' || c == '\'' || c == '\u{0060}' {
            let start = (line, col);
            let mut closed = false;
            step(&cs, &mut i, &mut line, &mut col);
            while i < cs.len() {
                if cs[i] == '\\' {
                    step(&cs, &mut i, &mut line, &mut col);
                    if i >= cs.len() {
                        break;
                    }
                    step(&cs, &mut i, &mut line, &mut col);
                } else if cs[i] == c {
                    step(&cs, &mut i, &mut line, &mut col);
                    closed = true;
                    break;
                } else {
                    step(&cs, &mut i, &mut line, &mut col);
                }
            }
            if !closed {
                return Err(format!("unterminated string at {}:{}", start.0, start.1));
            }
            continue;
        }
        if matches!(c, '{' | '[' | '(') {
            delimiters.push((c, line, col));
        } else if matches!(c, '}' | ']' | ')') {
            let expected = match c {
                '}' => '{',
                ']' => '[',
                ')' => '(',
                _ => unreachable!(),
            };
            match delimiters.pop() {
                Some((open, _, _)) if open == expected => {}
                _ => return Err(format!("unexpected closing delimiter {c} at {line}:{col}")),
            }
        }
        step(&cs, &mut i, &mut line, &mut col);
    }
    if let Some((open, start_line, start_col)) = delimiters.last() {
        return Err(format!("unclosed delimiter {open} at {start_line}:{start_col}"));
    }
    Ok(tokens(src))
}

#[cfg(test)]
mod tests{
 use super::*;
 #[test]fn strips_strings_comments_and_nested_comments(){
  let t=tokens("/* defer /* await */ defer */ // await\n \"defer\" 'await' `defer` value");
  assert_eq!(t.len(),1);assert_eq!(t[0].value,"value");
 }
 #[test]fn line_positions(){
  let t=tokens("hi\n  await");
  assert_eq!((t[1].line,t[1].col),(2,3));
 }
}


